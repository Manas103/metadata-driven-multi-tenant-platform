package com.manas.metaplatform.compiler;

import com.manas.metaplatform.dsl.ParsedQuery;
import com.manas.metaplatform.dsl.Parser;
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
 */
@Component
public final class QueryExecutor {

    private final TenantScopingRewriter rewriter;
    private final SqlCompiler compiler;
    private final JdbcTemplate jdbcTemplate;

    public QueryExecutor(TenantScopingRewriter rewriter, SqlCompiler compiler, JdbcTemplate jdbcTemplate) {
        this.rewriter = rewriter;
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
        CompiledSql compiled = compileOnly(dsl, tenantId);
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
