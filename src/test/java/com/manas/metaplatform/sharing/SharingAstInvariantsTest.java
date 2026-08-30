package com.manas.metaplatform.sharing;

import com.manas.metaplatform.dsl.ast.AndExpr;
import com.manas.metaplatform.dsl.ast.Comparison;
import com.manas.metaplatform.dsl.ast.Literal;
import com.manas.metaplatform.dsl.ast.OrExpr;
import com.manas.metaplatform.dsl.ast.Operator;
import com.manas.metaplatform.dsl.ast.SharingScopeNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Structural assertions on the WHERE AST for {@link SharingScopeNode}, the mirror image of
 * {@code compiler.AstInvariantsTest} for {@link com.manas.metaplatform.dsl.ast.TenantScopeNode}:
 * independent of any database or SQL text, does exactly one sharing node for the expected
 * viewer sit at the top level of the tree, reachable only through AND.
 */
class SharingAstInvariantsTest {

    private static final Comparison SOME_COMPARISON =
            new Comparison("amount", Operator.GT, new Literal.NumberLiteral(java.math.BigDecimal.ONE));

    @Test
    void bareSharingNodePasses() {
        SharingScopeNode node = new SharingScopeNode(7, List.of(7L, 8L));
        assertThatCode(() -> SharingAstInvariants.assertSharingScopedAtRoot(node, 7))
                .doesNotThrowAnyException();
    }

    @Test
    void sharingNodeAndedWithTenantAndUserClausePasses() {
        AndExpr root = new AndExpr(new SharingScopeNode(7, List.of(7L)),
                new AndExpr(new com.manas.metaplatform.dsl.ast.TenantScopeNode(1), SOME_COMPARISON));
        assertThatCode(() -> SharingAstInvariants.assertSharingScopedAtRoot(root, 7))
                .doesNotThrowAnyException();
    }

    @Test
    void missingSharingNodeFails() {
        assertThatThrownBy(() -> SharingAstInvariants.assertSharingScopedAtRoot(SOME_COMPARISON, 7))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no SharingScopeNode");
    }

    @Test
    void sharingNodeBuriedUnderOrFails() {
        // The same failure shape compiler.AstInvariantsTest guards against for the tenant node:
        // a sharing predicate that has been ORed in anywhere stops restricting every row.
        OrExpr root = new OrExpr(new SharingScopeNode(7, List.of(7L)), SOME_COMPARISON);
        assertThatThrownBy(() -> SharingAstInvariants.assertSharingScopedAtRoot(root, 7))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no SharingScopeNode");
    }

    @Test
    void wrongViewerIdFails() {
        assertThatThrownBy(() -> SharingAstInvariants.assertSharingScopedAtRoot(
                new SharingScopeNode(7, List.of(7L)), 8))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("carries viewer 7");
    }

    @Test
    void duplicateSharingNodesFail() {
        AndExpr root = new AndExpr(new SharingScopeNode(7, List.of(7L)), new SharingScopeNode(7, List.of(7L)));
        assertThatThrownBy(() -> SharingAstInvariants.assertSharingScopedAtRoot(root, 7))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("2 SharingScopeNode");
    }
}
