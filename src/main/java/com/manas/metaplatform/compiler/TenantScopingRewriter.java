package com.manas.metaplatform.compiler;

import com.manas.metaplatform.dsl.ParsedQuery;
import com.manas.metaplatform.dsl.ast.AndExpr;
import com.manas.metaplatform.dsl.ast.Expr;
import com.manas.metaplatform.dsl.ast.SelectQuery;
import com.manas.metaplatform.dsl.ast.TenantScopeNode;
import org.springframework.stereotype.Component;

/**
 * The one and only place a {@link TenantScopeNode} is created. Takes a tenant-less
 * {@link ParsedQuery} and a tenant id and returns a {@link ScopedQuery} whose WHERE tree
 * has {@code tenant_id = ?} ANDed in at the root, as a real AST node, before any code
 * generation happens. If the parsed query had no WHERE clause at all, the tenant predicate
 * becomes the entire WHERE tree; it is never optional or conditionally attached.
 */
@Component
public final class TenantScopingRewriter {

    public ScopedQuery scope(ParsedQuery parsed, long tenantId) {
        SelectQuery query = parsed.query();
        TenantScopeNode tenantNode = new TenantScopeNode(tenantId);
        Expr scopedWhere = query.where()
                .map(userWhere -> (Expr) new AndExpr(tenantNode, userWhere))
                .orElse(tenantNode);
        return new ScopedQuery(tenantId, query.selectFields(), query.objectName(), scopedWhere);
    }
}
