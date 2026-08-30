package com.manas.metaplatform.sharing;

import com.manas.metaplatform.compiler.AstInvariants;
import com.manas.metaplatform.compiler.Scoped;
import com.manas.metaplatform.dsl.ast.Expr;

import java.util.List;

/**
 * A query that has been through both {@code compiler.TenantScopingRewriter} and
 * {@link SharingScopingRewriter}, and is therefore safe to lower to SQL with both the tenant
 * predicate and the record-level sharing filter enforced. The constructor is
 * package-private: the only place in the entire codebase that can produce a
 * {@code SharingScopedQuery} is {@link SharingScopingRewriter#scope}, mirroring exactly how
 * {@code compiler.ScopedQuery}'s package-private constructor restricts it to
 * {@code TenantScopingRewriter}. There is structurally no path from DSL text to a sharing-
 * filtered {@code PreparedStatement} that skips either rewriter.
 */
public final class SharingScopedQuery implements Scoped {

    private final long tenantId;
    private final List<String> selectFields;
    private final String objectName;
    private final Expr where;
    private final long viewerUserId;

    SharingScopedQuery(long tenantId, List<String> selectFields, String objectName, Expr where,
                        long viewerUserId) {
        this.tenantId = tenantId;
        this.selectFields = List.copyOf(selectFields);
        this.objectName = objectName;
        this.where = where;
        this.viewerUserId = viewerUserId;
        // Defense in depth, the same pattern compiler.ScopedQuery uses: re-check both
        // invariants here so a future bug in either rewriter fails loudly at construction
        // time rather than silently producing an under-scoped query.
        AstInvariants.assertTenantScopedAtRoot(where, tenantId);
        SharingAstInvariants.assertSharingScopedAtRoot(where, viewerUserId);
    }

    @Override
    public long tenantId() {
        return tenantId;
    }

    @Override
    public List<String> selectFields() {
        return selectFields;
    }

    @Override
    public String objectName() {
        return objectName;
    }

    @Override
    public Expr where() {
        return where;
    }

    public long viewerUserId() {
        return viewerUserId;
    }
}
