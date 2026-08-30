package com.manas.metaplatform.reference;

import com.manas.metaplatform.catalog.SchemaCatalog;
import com.manas.metaplatform.domain.FieldDefinition;
import com.manas.metaplatform.domain.FieldType;
import com.manas.metaplatform.domain.ObjectDefinition;
import com.manas.metaplatform.repository.FieldDefinitionRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A second, independent translator for the same DSL grammar, used purely as a diff oracle
 * for the reference-translator claim. It shares nothing with {@code dsl.Lexer},
 * {@code dsl.Parser}, {@code compiler.SqlCompiler} or the AST types in {@code dsl.ast}:
 *
 * <ul>
 *   <li>Tokenizing is one regex pass over the whole query text ({@link #TOKEN_PATTERN}),
 *       not a hand-rolled character-by-character scanner.</li>
 *   <li>The WHERE clause is not built into an AST class hierarchy at all; parsing directly
 *       produces a {@code Predicate<Map<String,Object>>} by functional composition
 *       ({@code Predicate::and}/{@code Predicate::or}) as it recognizes each grammar rule,
 *       so there is no {@code Expr}/{@code Comparison}/{@code AndExpr} equivalent here.</li>
 *   <li>Execution does not generate per-field {@code LEFT JOIN} SQL. It runs one flat query
 *       that pulls every (record, field) row for the tenant and object into memory, then
 *       filters and projects in Java. The main compiler pushes the WHERE clause down to
 *       SQL; this interpreter pulls the data up and evaluates the WHERE clause itself.</li>
 *   <li>Tenant scoping is enforced by this class's own {@code WHERE r.tenant_id = ?} in its
 *       own query, written independently of {@code compiler.TenantScopingRewriter}.</li>
 * </ul>
 *
 * <p>The two implementations only share {@code catalog.SchemaCatalog} (metadata lookups)
 * and, unavoidably, the database schema itself. Neither of those participates in query
 * lowering, which is the logic under test.
 */
@Component
public final class ReferenceInterpreter {

    private static final Pattern TOKEN_PATTERN = Pattern.compile(
            "'(?:[^']|'')*'" // string literal, doubled '' as escaped quote
                    + "|!=|<>|>=|<=|[=<>]"
                    + "|[(),]"
                    + "|[A-Za-z_][A-Za-z0-9_]*"
                    + "|\\d+(?:\\.\\d+)?");

    private final SchemaCatalog schemaCatalog;
    private final FieldDefinitionRepository fieldDefinitionRepository;
    private final JdbcTemplate jdbcTemplate;

    public ReferenceInterpreter(SchemaCatalog schemaCatalog,
                                 FieldDefinitionRepository fieldDefinitionRepository,
                                 JdbcTemplate jdbcTemplate) {
        this.schemaCatalog = schemaCatalog;
        this.fieldDefinitionRepository = fieldDefinitionRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Result row order matches ascending record id, identical to the main compiler's ORDER BY r.id. */
    public List<Map<String, Object>> execute(String dsl, long tenantId) {
        List<String> tokens = tokenize(dsl);
        Cursor cur = new Cursor(tokens);

        cur.expectWord("SELECT");
        List<String> selectFields = parseFieldList(cur);
        cur.expectWord("FROM");
        String objectName = cur.advance();

        Predicate<Map<String, Object>> predicate = row -> true;
        if (cur.hasNext() && cur.peekIsWord("WHERE")) {
            cur.advance();
            predicate = parseOr(cur);
        }

        ObjectDefinition objectDefinition = schemaCatalog.resolveObject(tenantId, objectName);
        List<FieldDefinition> allFields = fieldDefinitionRepository.findByObjectDefinitionId(objectDefinition.getId());
        Map<Long, FieldDefinition> byId = new LinkedHashMap<>();
        for (FieldDefinition fd : allFields) {
            byId.put(fd.getId(), fd);
        }

        // Materialize every (record, field) row for this tenant/object, then filter and
        // project in Java: the "pull everything up, decide in memory" strategy, as opposed
        // to the compiler's "push the decision down into the SQL WHERE clause" strategy.
        Map<Long, Map<String, Object>> byRecord = new LinkedHashMap<>();
        jdbcTemplate.query(
                "SELECT r.id AS rid, fv.field_definition_id AS fid, fv.string_value AS sv, "
                        + "fv.number_value AS nv, fv.boolean_value AS bv "
                        + "FROM records r LEFT JOIN field_values fv ON fv.record_id = r.id "
                        + "WHERE r.tenant_id = ? AND r.object_definition_id = ? ORDER BY r.id",
                rs -> {
                    long recordId = rs.getLong("rid");
                    Map<String, Object> fields = byRecord.computeIfAbsent(recordId, k -> new LinkedHashMap<>());
                    long fid = rs.getLong("fid");
                    if (rs.wasNull()) {
                        return;
                    }
                    FieldDefinition fd = byId.get(fid);
                    if (fd == null) {
                        return;
                    }
                    Object value = switch (fd.getFieldType()) {
                        case STRING -> rs.getString("sv");
                        case NUMBER -> rs.getBigDecimal("nv");
                        case BOOLEAN -> rs.getObject("bv") == null ? null : rs.getBoolean("bv");
                    };
                    fields.put(fd.getName(), value);
                },
                tenantId, objectDefinition.getId());

        List<Map<String, Object>> results = new ArrayList<>();
        for (Map.Entry<Long, Map<String, Object>> entry : byRecord.entrySet()) {
            if (!predicate.test(entry.getValue())) {
                continue;
            }
            Map<String, Object> projected = new LinkedHashMap<>();
            projected.put("record_id", entry.getKey());
            for (String field : selectFields) {
                projected.put(field, entry.getValue().get(field));
            }
            results.add(projected);
        }
        return results;
    }

    /**
     * The sharing-filtered variant of {@link #execute}, independent of
     * {@code sharing.RoleHierarchyResolver} in both direction and data access: instead of
     * breadth-first descending from the viewer's role to precompute every visible owner
     * before running the query (the main compiler's approach, baked into the
     * {@link com.manas.metaplatform.dsl.ast.SharingScopeNode} it emits), this walks upward
     * from each candidate row's owner's role, one parent hop at a time, checking whether the
     * viewer's own role is reached. Same predicate, computed the opposite direction, over
     * data pulled with this class's own {@code JdbcTemplate} queries rather than the
     * repositories {@code RoleHierarchyResolver} uses.
     */
    public List<Map<String, Object>> executeWithSharing(String dsl, long tenantId, long viewerUserId) {
        List<Map<String, Object>> tenantScopedRows = execute(dsl, tenantId);
        if (tenantScopedRows.isEmpty()) {
            return tenantScopedRows;
        }

        Map<Long, Long> ownerUserIdByRecordId = new LinkedHashMap<>();
        jdbcTemplate.query("SELECT id, owner_user_id FROM records WHERE tenant_id = ?",
                rs -> {
                    long recordId = rs.getLong("id");
                    long ownerUserId = rs.getLong("owner_user_id");
                    if (!rs.wasNull()) {
                        ownerUserIdByRecordId.put(recordId, ownerUserId);
                    }
                }, tenantId);

        Map<Long, Long> roleIdByUserId = new LinkedHashMap<>();
        jdbcTemplate.query("SELECT id, role_id FROM app_users WHERE tenant_id = ?",
                (RowCallbackHandler) rs -> roleIdByUserId.put(rs.getLong("id"), rs.getLong("role_id")), tenantId);

        Map<Long, Long> parentRoleIdByRoleId = new LinkedHashMap<>();
        jdbcTemplate.query("SELECT id, parent_role_id FROM roles WHERE tenant_id = ?",
                rs -> {
                    long roleId = rs.getLong("id");
                    long parentRoleId = rs.getLong("parent_role_id");
                    parentRoleIdByRoleId.put(roleId, rs.wasNull() ? null : parentRoleId);
                }, tenantId);

        Set<Long> explicitlySharedRecordIds = new LinkedHashSet<>();
        jdbcTemplate.query("SELECT record_id FROM record_shares WHERE shared_with_user_id = ?",
                (RowCallbackHandler) rs -> explicitlySharedRecordIds.add(rs.getLong("record_id")), viewerUserId);

        Long viewerRoleId = roleIdByUserId.get(viewerUserId);

        List<Map<String, Object>> visibleRows = new ArrayList<>();
        for (Map<String, Object> row : tenantScopedRows) {
            Long recordId = (Long) row.get("record_id");
            Long ownerUserId = ownerUserIdByRecordId.get(recordId);
            boolean visible = explicitlySharedRecordIds.contains(recordId)
                    || (ownerUserId != null && ownerUserId.equals(viewerUserId))
                    || (ownerUserId != null && viewerRoleId != null
                    && ownerRoleReportsUpToViewerRole(roleIdByUserId.get(ownerUserId), viewerRoleId, parentRoleIdByRoleId));
            if (visible) {
                visibleRows.add(row);
            }
        }
        return visibleRows;
    }

    private boolean ownerRoleReportsUpToViewerRole(Long ownerRoleId, long viewerRoleId,
                                                     Map<Long, Long> parentRoleIdByRoleId) {
        Long current = ownerRoleId;
        int guard = 0; // cycle guard; the role graph is a tree, but never trust that blindly
        while (current != null && guard++ < 1000) {
            if (current == viewerRoleId) {
                return true;
            }
            current = parentRoleIdByRoleId.get(current);
        }
        return false;
    }

    private List<String> parseFieldList(Cursor cur) {
        List<String> fields = new ArrayList<>();
        fields.add(cur.advance());
        while (cur.hasNext() && cur.peek().equals(",")) {
            cur.advance();
            fields.add(cur.advance());
        }
        return fields;
    }

    private Predicate<Map<String, Object>> parseOr(Cursor cur) {
        Predicate<Map<String, Object>> left = parseAnd(cur);
        while (cur.hasNext() && cur.peekIsWord("OR")) {
            cur.advance();
            Predicate<Map<String, Object>> right = parseAnd(cur);
            left = left.or(right);
        }
        return left;
    }

    private Predicate<Map<String, Object>> parseAnd(Cursor cur) {
        Predicate<Map<String, Object>> left = parsePrimary(cur);
        while (cur.hasNext() && cur.peekIsWord("AND")) {
            cur.advance();
            Predicate<Map<String, Object>> right = parsePrimary(cur);
            left = left.and(right);
        }
        return left;
    }

    private Predicate<Map<String, Object>> parsePrimary(Cursor cur) {
        if (cur.peek().equals("(")) {
            cur.advance();
            Predicate<Map<String, Object>> inner = parseOr(cur);
            cur.expectLiteral(")");
            return inner;
        }
        return parseComparison(cur);
    }

    private Predicate<Map<String, Object>> parseComparison(Cursor cur) {
        String field = cur.advance();
        String op = cur.advance();
        String literalToken = cur.advance();

        if (literalToken.startsWith("'")) {
            String value = literalToken.substring(1, literalToken.length() - 1).replace("''", "'");
            return row -> compareString(row.get(field), op, value);
        }
        if (literalToken.equalsIgnoreCase("TRUE") || literalToken.equalsIgnoreCase("FALSE")) {
            boolean value = literalToken.equalsIgnoreCase("TRUE");
            return row -> compareBoolean(row.get(field), op, value);
        }
        BigDecimal value = new BigDecimal(literalToken);
        return row -> compareNumber(row.get(field), op, value);
    }

    private boolean compareString(Object actual, String op, String expected) {
        if (actual == null) {
            return false;
        }
        String actualStr = (String) actual;
        return switch (op) {
            case "=" -> actualStr.equals(expected);
            case "!=", "<>" -> !actualStr.equals(expected);
            case "LIKE" -> likePattern(expected).matcher(actualStr).matches();
            default -> throw new IllegalStateException("operator " + op + " not valid for a string field");
        };
    }

    private boolean compareNumber(Object actual, String op, BigDecimal expected) {
        if (actual == null) {
            return false;
        }
        int cmp = ((BigDecimal) actual).compareTo(expected);
        return switch (op) {
            case "=" -> cmp == 0;
            case "!=", "<>" -> cmp != 0;
            case ">" -> cmp > 0;
            case ">=" -> cmp >= 0;
            case "<" -> cmp < 0;
            case "<=" -> cmp <= 0;
            default -> throw new IllegalStateException("operator " + op + " not valid for a number field");
        };
    }

    private boolean compareBoolean(Object actual, String op, boolean expected) {
        if (actual == null) {
            return false;
        }
        boolean actualBool = (Boolean) actual;
        return switch (op) {
            case "=" -> actualBool == expected;
            case "!=", "<>" -> actualBool != expected;
            default -> throw new IllegalStateException("operator " + op + " not valid for a boolean field");
        };
    }

    private Pattern likePattern(String sqlPattern) {
        StringBuilder regex = new StringBuilder();
        for (char ch : sqlPattern.toCharArray()) {
            switch (ch) {
                case '%' -> regex.append(".*");
                case '_' -> regex.append('.');
                default -> {
                    if ("\\.^$|?*+()[]{}".indexOf(ch) >= 0) {
                        regex.append('\\');
                    }
                    regex.append(ch);
                }
            }
        }
        return Pattern.compile(regex.toString(), Pattern.DOTALL);
    }

    private List<String> tokenize(String dsl) {
        List<String> tokens = new ArrayList<>();
        Matcher m = TOKEN_PATTERN.matcher(dsl);
        int expectedPos = 0;
        while (m.find()) {
            // Anything skipped between matches must be whitespace only; a stray character
            // (e.g. an unsupported operator) is a syntax error rather than being silently
            // dropped, matching the strictness of the hand-rolled Lexer.
            String skipped = dsl.substring(expectedPos, m.start());
            if (!skipped.isBlank()) {
                throw new IllegalArgumentException("unexpected input '" + skipped.trim() + "' in query");
            }
            tokens.add(m.group());
            expectedPos = m.end();
        }
        String trailing = dsl.substring(expectedPos);
        if (!trailing.isBlank()) {
            throw new IllegalArgumentException("unexpected trailing input '" + trailing.trim() + "' in query");
        }
        return tokens;
    }

    private static final class Cursor {
        private final List<String> tokens;
        private int pos;

        Cursor(List<String> tokens) {
            this.tokens = tokens;
        }

        boolean hasNext() {
            return pos < tokens.size();
        }

        String peek() {
            return tokens.get(pos);
        }

        boolean peekIsWord(String word) {
            return pos < tokens.size() && tokens.get(pos).equalsIgnoreCase(word);
        }

        String advance() {
            return tokens.get(pos++);
        }

        void expectWord(String word) {
            if (!peekIsWord(word)) {
                throw new IllegalArgumentException("expected " + word + " at token index " + pos);
            }
            pos++;
        }

        void expectLiteral(String literal) {
            if (pos >= tokens.size() || !tokens.get(pos).equals(literal)) {
                throw new IllegalArgumentException("expected '" + literal + "' at token index " + pos);
            }
            pos++;
        }
    }
}
