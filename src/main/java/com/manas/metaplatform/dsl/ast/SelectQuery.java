package com.manas.metaplatform.dsl.ast;

import java.util.List;
import java.util.Optional;

/**
 * The full parsed query: a select list, an object name, and an optional WHERE tree. This
 * is the un-scoped shape the {@code Parser} produces; it deliberately has no tenant id and
 * no method that yields SQL. See {@code dsl.ParsedQuery} and
 * {@code compiler.TenantScopingRewriter} for why that gap is structural, not incidental.
 */
public record SelectQuery(List<String> selectFields, String objectName, Optional<Expr> where) {
}
