package com.manas.metaplatform.compiler;

import com.manas.metaplatform.catalog.SchemaCatalog;
import com.manas.metaplatform.domain.FieldDefinition;
import com.manas.metaplatform.domain.FieldType;
import com.manas.metaplatform.domain.ObjectDefinition;
import com.manas.metaplatform.dsl.ast.AndExpr;
import com.manas.metaplatform.dsl.ast.Comparison;
import com.manas.metaplatform.dsl.ast.Expr;
import com.manas.metaplatform.dsl.ast.Literal;
import com.manas.metaplatform.dsl.ast.OrExpr;
import com.manas.metaplatform.dsl.ast.SharingScopeNode;
import com.manas.metaplatform.dsl.ast.TenantScopeNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lowers a {@link Scoped} query (never a bare {@code ParsedQuery}, see {@link ScopedQuery}'s
 * javadoc) to parameterized SQL against the EAV storage schema: {@code records} plus one
 * {@code LEFT JOIN field_values} per distinct field referenced anywhere in the query
 * (SELECT list or WHERE tree). No literal value, tenant id, or sharing-visibility id is ever
 * concatenated into the SQL text; everything the query supplies at compile time becomes one
 * {@code ?} placeholder and one entry in {@link CompiledSql#params()}, in the same order.
 * {@link ScopedQuery} (tenant scoping only) and {@code sharing.SharingScopedQuery} (tenant
 * scoping plus the record-level sharing filter) both implement {@link Scoped}, so both are
 * lowered by this exact same code generator; the sharing filter is not a second compiler.
 *
 * <p>Every AND/OR node is emitted as an explicitly parenthesized {@code (left OP right)}
 * around its two already-fully-parenthesized (or atomic) operands. That is a deliberate
 * choice, not an incidental one: it is exactly what makes {@code (tenant_id = ? AND
 * (user OR predicate))} always bind the way the AST says it binds, regardless of how deep
 * or how OR-heavy the user's own WHERE clause is. A naive implementation that renders the
 * user's WHERE clause to a SQL string first and then string-concatenates
 * {@code "tenant_id = ? AND " + userSql} would get this wrong the moment the user clause's
 * top-level operator is OR; this compiler never does that because it never has a bare SQL
 * string for the user clause in isolation; it only ever has the whole already-rewritten AST.
 */
@Component
public final class SqlCompiler {

    private final SchemaCatalog schemaCatalog;

    public SqlCompiler(SchemaCatalog schemaCatalog) {
        this.schemaCatalog = schemaCatalog;
    }

    public CompiledSql compile(Scoped query) {
        ObjectDefinition objectDefinition = schemaCatalog.resolveObject(query.tenantId(), query.objectName());

        Set<String> referencedFields = new LinkedHashSet<>(query.selectFields());
        collectFieldNames(query.where(), referencedFields);

        Map<String, FieldJoin> joins = new LinkedHashMap<>();
        int aliasCounter = 0;
        for (String fieldName : referencedFields) {
            FieldDefinition fd = schemaCatalog.resolveField(objectDefinition.getId(), fieldName);
            aliasCounter++;
            joins.put(fieldName, new FieldJoin("fv" + aliasCounter, fd));
        }

        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder();

        List<CompiledSql.SelectColumn> selectColumns = new ArrayList<>();
        sql.append("SELECT r.id AS record_id");
        for (String field : query.selectFields()) {
            FieldJoin join = joins.get(field);
            sql.append(", ").append(join.alias()).append('.').append(columnFor(join.fieldDefinition().getFieldType()))
                    .append(" AS ").append(join.alias());
            selectColumns.add(new CompiledSql.SelectColumn(field, join.alias(), join.fieldDefinition().getFieldType()));
        }
        sql.append(" FROM records r");
        for (FieldJoin join : joins.values()) {
            sql.append(" LEFT JOIN field_values ").append(join.alias())
                    .append(" ON ").append(join.alias()).append(".record_id = r.id")
                    .append(" AND ").append(join.alias()).append(".field_definition_id = ?");
            params.add(join.fieldDefinition().getId());
        }
        sql.append(" WHERE r.object_definition_id = ?");
        params.add(objectDefinition.getId());
        sql.append(" AND ");
        sql.append(emit(query.where(), joins, params));
        sql.append(" ORDER BY r.id");

        return new CompiledSql(sql.toString(), params, selectColumns);
    }

    private void collectFieldNames(Expr expr, Set<String> out) {
        if (expr instanceof Comparison c) {
            out.add(c.fieldName());
        } else if (expr instanceof AndExpr a) {
            collectFieldNames(a.left(), out);
            collectFieldNames(a.right(), out);
        } else if (expr instanceof OrExpr o) {
            collectFieldNames(o.left(), out);
            collectFieldNames(o.right(), out);
        }
        // TenantScopeNode and SharingScopeNode reference no DSL field; nothing to collect.
    }

    private String emit(Expr expr, Map<String, FieldJoin> joins, List<Object> params) {
        if (expr instanceof TenantScopeNode t) {
            params.add(t.tenantId());
            return "r.tenant_id = ?";
        }
        if (expr instanceof SharingScopeNode s) {
            return emitSharingScope(s, params);
        }
        if (expr instanceof Comparison c) {
            return emitComparison(c, joins, params);
        }
        if (expr instanceof AndExpr a) {
            String left = emit(a.left(), joins, params);
            String right = emit(a.right(), joins, params);
            return "(" + left + " AND " + right + ")";
        }
        if (expr instanceof OrExpr o) {
            String left = emit(o.left(), joins, params);
            String right = emit(o.right(), joins, params);
            return "(" + left + " OR " + right + ")";
        }
        throw new IllegalStateException("unreachable: unknown Expr node " + expr);
    }

    /**
     * Lowers a {@link SharingScopeNode} directly against the physical {@code records} and
     * {@code record_shares} tables, exactly the way {@code emit} lowers a
     * {@link TenantScopeNode} directly against {@code records.tenant_id}: never through the
     * DSL field/join machinery, and never as a subquery string built anywhere but here. A
     * record is visible if its owner is one of the ids the role-hierarchy resolver already
     * computed for this viewer, or if an explicit {@code record_shares} row grants the
     * viewer access to that exact record; the OR of both is what {@code SharingScopeNode}'s
     * javadoc calls out as the reason it always carries both checks together.
     */
    private String emitSharingScope(SharingScopeNode s, List<Object> params) {
        List<Long> owners = s.visibleOwnerUserIds();
        String ownerClause;
        if (owners.isEmpty()) {
            // No ownership/role-hierarchy visibility at all for this viewer; only an explicit
            // share grant can make anything visible. "1 = 0" rather than an empty IN(), which
            // is not legal SQL.
            ownerClause = "1 = 0";
        } else {
            String placeholders = String.join(", ", Collections.nCopies(owners.size(), "?"));
            ownerClause = "r.owner_user_id IN (" + placeholders + ")";
            params.addAll(owners);
        }
        params.add(s.viewerUserId());
        return "(" + ownerClause
                + " OR EXISTS (SELECT 1 FROM record_shares rs WHERE rs.record_id = r.id AND rs.shared_with_user_id = ?))";
    }

    private String emitComparison(Comparison c, Map<String, FieldJoin> joins, List<Object> params) {
        FieldJoin join = joins.get(c.fieldName());
        FieldType type = join.fieldDefinition().getFieldType();
        validateOperator(c, type);
        String column = join.alias() + "." + columnFor(type);
        String op = sqlOperator(c);
        params.add(literalValue(c.literal(), type));
        return column + " " + op + " ?";
    }

    private void validateOperator(Comparison c, FieldType type) {
        boolean ok = switch (type) {
            case STRING -> switch (c.operator()) {
                case EQ, NEQ, LIKE -> true;
                default -> false;
            };
            case NUMBER -> switch (c.operator()) {
                case EQ, NEQ, GT, GTE, LT, LTE -> true;
                default -> false;
            };
            case BOOLEAN -> switch (c.operator()) {
                case EQ, NEQ -> true;
                default -> false;
            };
        };
        if (!ok) {
            throw new DslCompileException(
                    "operator " + c.operator() + " is not valid for field '" + c.fieldName()
                            + "' of type " + type);
        }
        boolean literalMatches = switch (type) {
            case STRING -> c.literal() instanceof Literal.StringLiteral;
            case NUMBER -> c.literal() instanceof Literal.NumberLiteral;
            case BOOLEAN -> c.literal() instanceof Literal.BooleanLiteral;
        };
        if (!literalMatches) {
            throw new DslCompileException(
                    "literal for field '" + c.fieldName() + "' does not match its declared type " + type);
        }
    }

    private String sqlOperator(Comparison c) {
        return switch (c.operator()) {
            case EQ -> "=";
            case NEQ -> "!=";
            case GT -> ">";
            case GTE -> ">=";
            case LT -> "<";
            case LTE -> "<=";
            case LIKE -> "LIKE";
        };
    }

    private Object literalValue(Literal literal, FieldType type) {
        return switch (literal) {
            case Literal.StringLiteral s -> s.value();
            case Literal.NumberLiteral n -> n.value();
            case Literal.BooleanLiteral b -> b.value();
        };
    }

    private String columnFor(FieldType type) {
        return switch (type) {
            case STRING -> "string_value";
            case NUMBER -> "number_value";
            case BOOLEAN -> "boolean_value";
        };
    }

    private record FieldJoin(String alias, FieldDefinition fieldDefinition) {
    }
}
