package com.manas.metaplatform.compiler;

import com.manas.metaplatform.dsl.ParsedQuery;
import com.manas.metaplatform.dsl.Parser;
import com.manas.metaplatform.sharing.SharingScopedQuery;
import com.manas.metaplatform.sharing.SharingScopingRewriter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The end-to-end path: DSL text and a tenant id in, rows out. This is the only place that
 * chains {@link Parser#parse}, {@link TenantScopingRewriter#scope} and
 * {@link SqlCompiler#compile} together and actually executes the result; every step in
 * between passes through the type that step is supposed to produce, so this method cannot
 * skip the tenant-scoping rewrite even by accident, it does not compile otherwise.
 *
 * <p>{@link #executeWithSharing} chains one further step, {@link SharingScopingRewriter#scope},
 * before compiling, so a caller that needs the record-level sharing filter enforced
 * likewise cannot skip it by accident; the plain tenant-only {@link #execute} path used by
 * the original tenant-isolation claim is left entirely unchanged.
 */
@Component
public final class QueryExecutor {

    private final TenantScopingRewriter rewriter;
    private final SharingScopingRewriter sharingScopingRewriter;
    private final SqlCompiler compiler;
    private final JdbcTemplate jdbcTemplate;

    public QueryExecutor(TenantScopingRewriter rewriter, SharingScopingRewriter sharingScopingRewriter,
                          SqlCompiler compiler, JdbcTemplate jdbcTemplate) {
        this.rewriter = rewriter;
        this.sharingScopingRewriter = sharingScopingRewriter;
        this.compiler = compiler;
        this.jdbcTemplate = jdbcTemplate;
    }

    public CompiledSql compileOnly(String dsl, long tenantId) {
        ParsedQuery parsed = Parser.parse(dsl);
        ScopedQuery scoped = rewriter.scope(parsed, tenantId);
        return compiler.compile(scoped);
    }

    /** Result row order matches ascending record id, identical to {@code ReferenceInterpreter}. */
    public List<Map<String, Object>> execute(String dsl, long tenantId) {
        return runQuery(compileOnly(dsl, tenantId));
    }

    /** Tenant scoping, then the record-level sharing filter, both injected into the AST. */
    public CompiledSql compileOnlyWithSharing(String dsl, long tenantId, long viewerUserId) {
        ParsedQuery parsed = Parser.parse(dsl);
        ScopedQuery tenantScoped = rewriter.scope(parsed, tenantId);
        SharingScopedQuery sharingScoped = sharingScopingRewriter.scope(tenantScoped, viewerUserId);
        return compiler.compile(sharingScoped);
    }

    /** Result row order matches ascending record id, identical to {@code ReferenceInterpreter}. */
    public List<Map<String, Object>> executeWithSharing(String dsl, long tenantId, long viewerUserId) {
        return runQuery(compileOnlyWithSharing(dsl, tenantId, viewerUserId));
    }

    private List<Map<String, Object>> runQuery(CompiledSql compiled) {
        return jdbcTemplate.query(compiled.sql(), rs -> {
            List<Map<String, Object>> rows = new java.util.ArrayList<>();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("record_id", rs.getLong("record_id"));
                for (CompiledSql.SelectColumn col : compiled.selectColumns()) {
                    Object value = switch (col.type()) {
                        case STRING -> rs.getString(col.alias());
                        case NUMBER -> rs.getBigDecimal(col.alias());
                        case BOOLEAN -> rs.getObject(col.alias()) == null ? null : rs.getBoolean(col.alias());
                    };
                    row.put(col.fieldName(), value);
                }
                rows.add(row);
            }
            return rows;
        }, compiled.params().toArray());
    }
}
