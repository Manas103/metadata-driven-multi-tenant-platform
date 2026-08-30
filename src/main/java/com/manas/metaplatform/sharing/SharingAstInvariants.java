package com.manas.metaplatform.sharing;

import com.manas.metaplatform.dsl.ast.AndExpr;
import com.manas.metaplatform.dsl.ast.Expr;
import com.manas.metaplatform.dsl.ast.SharingScopeNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Structural assertions on the WHERE AST for the sharing predicate, the mirror image of
 * {@code compiler.AstInvariants} for {@code TenantScopeNode}. These walk the tree and
 * inspect node types directly, so they are a proof about the AST's shape, not an observation
 * about behavior.
 */
public final class SharingAstInvariants {

    private SharingAstInvariants() {
    }

    /**
     * Asserts that exactly one {@link SharingScopeNode} for {@code expectedViewerUserId}
     * appears as a top-level conjunct of {@code root}, i.e. reachable from the root by
     * descending only through {@link AndExpr} nodes (never through an
     * {@link com.manas.metaplatform.dsl.ast.OrExpr}, since a sharing predicate ORed in
     * anywhere would not actually restrict every row).
     *
     * @throws IllegalStateException if the invariant does not hold
     */
    public static void assertSharingScopedAtRoot(Expr root, long expectedViewerUserId) {
        List<SharingScopeNode> found = new ArrayList<>();
        collectTopLevelSharingNodes(root, found);
        if (found.isEmpty()) {
            throw new IllegalStateException(
                    "sharing scoping invariant violated: no SharingScopeNode reachable from the WHERE "
                            + "root through AND-only descent");
        }
        if (found.size() > 1) {
            throw new IllegalStateException(
                    "sharing scoping invariant violated: " + found.size()
                            + " SharingScopeNode instances at the top level, expected exactly one");
        }
        long actual = found.get(0).viewerUserId();
        if (actual != expectedViewerUserId) {
            throw new IllegalStateException(
                    "sharing scoping invariant violated: SharingScopeNode carries viewer " + actual
                            + " but query was scoped for viewer " + expectedViewerUserId);
        }
    }

    private static void collectTopLevelSharingNodes(Expr node, List<SharingScopeNode> out) {
        if (node instanceof SharingScopeNode s) {
            out.add(s);
        } else if (node instanceof AndExpr a) {
            collectTopLevelSharingNodes(a.left(), out);
            collectTopLevelSharingNodes(a.right(), out);
        }
        // Deliberately do not descend into OrExpr or Comparison, same reasoning as
        // compiler.AstInvariants.collectTopLevelTenantNodes.
    }
}
