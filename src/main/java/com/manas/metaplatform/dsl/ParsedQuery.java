package com.manas.metaplatform.dsl;

import com.manas.metaplatform.dsl.ast.SelectQuery;

/**
 * The output of {@link Parser}: a parsed query with no tenant attached. This type is
 * deliberately a dead end. There is no method on it, or on anything reachable from it,
 * that produces SQL or a {@code java.sql.PreparedStatement}. The only way to make progress
 * from a {@code ParsedQuery} is {@code compiler.TenantScopingRewriter#scope}, which returns
 * a {@code compiler.ScopedQuery}, and only a {@code ScopedQuery} is an accepted parameter
 * type of {@code compiler.SqlCompiler#compile}. Forgetting to scope a query is therefore a
 * compile error in this codebase, not a runtime bug: nothing type-checks that hands a
 * {@code ParsedQuery} to the compiler.
 */
public record ParsedQuery(SelectQuery query) {
}
