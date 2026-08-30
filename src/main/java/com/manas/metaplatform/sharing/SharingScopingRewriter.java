package com.manas.metaplatform.sharing;

import com.manas.metaplatform.compiler.ScopedQuery;
import com.manas.metaplatform.dsl.ast.AndExpr;
import com.manas.metaplatform.dsl.ast.Expr;
import com.manas.metaplatform.dsl.ast.SharingScopeNode;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The one and only place a {@link SharingScopeNode} is created. Takes an already
 * tenant-scoped {@link ScopedQuery} and a viewing user id and returns a
 * {@link SharingScopedQuery} whose WHERE tree has the sharing predicate ANDed in at the
 * root, as a real AST node, before any code generation happens, exactly the way
 * {@code compiler.TenantScopingRewriter} injects {@code tenant_id = ?}. The tenant predicate
 * always stays reachable too: this rewriter never removes or rewrites the tree it is handed,
 * it only wraps it.
 */
@Component
public final class SharingScopingRewriter {

    private final RoleHierarchyResolver roleHierarchyResolver;

    public SharingScopingRewriter(RoleHierarchyResolver roleHierarchyResolver) {
        this.roleHierarchyResolver = roleHierarchyResolver;
    }

    public SharingScopedQuery scope(ScopedQuery tenantScoped, long viewerUserId) {
        List<Long> visibleOwnerUserIds =
                roleHierarchyResolver.visibleOwnerUserIds(tenantScoped.tenantId(), viewerUserId);
        SharingScopeNode sharingNode = new SharingScopeNode(viewerUserId, visibleOwnerUserIds);
        Expr combinedWhere = new AndExpr(sharingNode, tenantScoped.where());
        return new SharingScopedQuery(tenantScoped.tenantId(), tenantScoped.selectFields(),
                tenantScoped.objectName(), combinedWhere, viewerUserId);
    }
}
