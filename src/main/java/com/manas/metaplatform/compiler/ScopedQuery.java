package com.manas.metaplatform.compiler;

import com.manas.metaplatform.dsl.ast.Expr;
import com.manas.metaplatform.dsl.ast.SelectQuery;

import java.util.List;

/**
 * A query that has been through {@link TenantScopingRewriter} and is therefore safe to
 * lower to SQL. The constructor is package-private: the only place in the entire codebase
 * that can produce a {@code ScopedQuery} is {@link TenantScopingRewriter#scope}, and
 * {@link SqlCompiler#compile} accepts nothing else. That is the structural guarantee this
 * project is built around: there is no code path from DSL text to a
 * {@code PreparedStatement} that does not pass through the rewriter that injects
 * {@code tenant_id = ?}.
 */
public final class ScopedQuery {

    private final long tenantId;
    private final List<String> selectFields;
    private final String objectName;
    private final Expr where;

    ScopedQuery(long tenantId, List<String> selectFields, String objectName, Expr where) {
        this.tenantId = tenantId;
        this.selectFields = List.copyOf(selectFields);
        this.objectName = objectName;
        this.where = where;
        // Defense in depth: even though the only caller of this constructor is the
        // rewriter, re-check the invariant here so a future bug in the rewriter fails
        // loudly at construction time rather than silently producing an unscoped query.
        AstInvariants.assertTenantScopedAtRoot(where, tenantId);
    }

    public long tenantId() {
        return tenantId;
    }

    public List<String> selectFields() {
        return selectFields;
    }

    public String objectName() {
        return objectName;
    }

    /** The WHERE tree, always non-null: at minimum, it is a bare {@code TenantScopeNode}. */
    public Expr where() {
        return where;
    }

    public static SelectQuery unwrapForTesting(ScopedQuery scoped) {
        // Used only by tests that need to compare a ScopedQuery's shape against the
        // original SelectQuery's select list/object name; production code never needs
        // to go back to a SelectQuery.
        return new SelectQuery(scoped.selectFields, scoped.objectName, java.util.Optional.of(scoped.where));
    }
}
