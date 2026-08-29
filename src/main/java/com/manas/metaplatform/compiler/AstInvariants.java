package com.manas.metaplatform.compiler;

import com.manas.metaplatform.dsl.ast.AndExpr;
import com.manas.metaplatform.dsl.ast.Expr;
import com.manas.metaplatform.dsl.ast.TenantScopeNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Structural assertions on the WHERE AST. These are not integration-test pass/fail checks;
 * they walk the tree and inspect node types directly, which is what makes them a proof
 * about the AST's shape rather than an observation about behavior.
 */
public final class AstInvariants {

    private AstInvariants() {
    }

    /**
     * Asserts that exactly one {@link TenantScopeNode} for {@code expectedTenantId} appears
     * as a top-level conjunct of {@code root}, i.e. reachable from the root by descending
     * only through {@link AndExpr} nodes (never through an {@link com.manas.metaplatform.dsl.ast.OrExpr},
     * since a tenant predicate ORed in anywhere would not actually restrict every row).
     *
     * @throws IllegalStateException if the invariant does not hold
     */
    public static void assertTenantScopedAtRoot(Expr root, long expectedTenantId) {
        List<TenantScopeNode> found = new ArrayList<>();
        collectTopLevelTenantNodes(root, found);
        if (found.isEmpty()) {
            throw new IllegalStateException(
                    "tenant scoping invariant violated: no TenantScopeNode reachable from the WHERE "
                            + "root through AND-only descent");
        }
        if (found.size() > 1) {
            throw new IllegalStateException(
                    "tenant scoping invariant violated: " + found.size()
                            + " TenantScopeNode instances at the top level, expected exactly one");
        }
        long actual = found.get(0).tenantId();
        if (actual != expectedTenantId) {
            throw new IllegalStateException(
                    "tenant scoping invariant violated: TenantScopeNode carries tenant " + actual
                            + " but query was scoped for tenant " + expectedTenantId);
        }
    }

    private static void collectTopLevelTenantNodes(Expr node, List<TenantScopeNode> out) {
        if (node instanceof TenantScopeNode t) {
            out.add(t);
        } else if (node instanceof AndExpr a) {
            collectTopLevelTenantNodes(a.left(), out);
            collectTopLevelTenantNodes(a.right(), out);
        }
        // Deliberately do not descend into OrExpr or Comparison: a TenantScopeNode nested
        // under an OR does not restrict every row the query returns, so it would not
        // satisfy this invariant even if present somewhere in the tree.
    }
}
