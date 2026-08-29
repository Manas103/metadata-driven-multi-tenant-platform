package com.manas.metaplatform.compiler;

import com.manas.metaplatform.domain.FieldType;

import java.util.List;

/**
 * The result of compiling a {@link ScopedQuery}: parameterized SQL text with {@code ?}
 * placeholders and the ordered list of bind values, in the exact order the placeholders
 * appear. No literal value from the DSL query is ever interpolated into {@link #sql()};
 * every literal, and the tenant id, travels only through {@link #params()}.
 *
 * @param selectColumns the SELECT list, in order: each entry's {@code alias} is the SQL
 *                       column alias to read from the {@code ResultSet}, {@code fieldName}
 *                       is the original DSL field name to key the result row by, and
 *                       {@code type} says which typed getter to call.
 */
public record CompiledSql(String sql, List<Object> params, List<SelectColumn> selectColumns) {

    public record SelectColumn(String fieldName, String alias, FieldType type) {
    }
}
