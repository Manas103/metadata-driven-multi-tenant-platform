package com.manas.metaplatform.compiler;

import com.manas.metaplatform.dsl.ast.AndExpr;
import com.manas.metaplatform.dsl.ast.Comparison;
import com.manas.metaplatform.dsl.ast.Literal;
import com.manas.metaplatform.dsl.ast.OrExpr;
import com.manas.metaplatform.dsl.ast.Operator;
import com.manas.metaplatform.dsl.ast.TenantScopeNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Structural assertions on the WHERE AST, independent of any database or SQL text: does a
 * {@link TenantScopeNode} for the expected tenant actually sit at the top level of the
 * tree, reachable only through AND, never buried under an OR where it would stop
 * restricting every row.
 */
class AstInvariantsTest {

    private static final Comparison SOME_COMPARISON =
            new Comparison("age", Operator.GT, new Literal.NumberLiteral(java.math.BigDecimal.ONE));

    @Test
    void bareTenantNodePasses() {
        assertThatCode(() -> AstInvariants.assertTenantScopedAtRoot(new TenantScopeNode(7), 7))
                .doesNotThrowAnyException();
    }

    @Test
    void tenantNodeAndedWithUserClausePasses() {
        AndExpr root = new AndExpr(new TenantScopeNode(7), SOME_COMPARISON);
        assertThatCode(() -> AstInvariants.assertTenantScopedAtRoot(root, 7))
                .doesNotThrowAnyException();
    }

    @Test
    void tenantNodeNestedThreeAndsDeepStillPasses() {
        AndExpr root = new AndExpr(new AndExpr(new TenantScopeNode(7), SOME_COMPARISON), SOME_COMPARISON);
        assertThatCode(() -> AstInvariants.assertTenantScopedAtRoot(root, 7))
                .doesNotThrowAnyException();
    }

    @Test
    void missingTenantNodeFails() {
        assertThatThrownBy(() -> AstInvariants.assertTenantScopedAtRoot(SOME_COMPARISON, 7))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no TenantScopeNode");
    }

    @Test
    void tenantNodeBuriedUnderOrFails() {
        // This is exactly the shape a string-concatenation bug (WHERE tenant_id = ? AND user_or_clause,
        // where user_or_clause itself starts "OR") could produce if the predicate were ever built
        // wrong: the tenant scope stops restricting every row once it's under an OR.
        OrExpr root = new OrExpr(new TenantScopeNode(7), SOME_COMPARISON);
        assertThatThrownBy(() -> AstInvariants.assertTenantScopedAtRoot(root, 7))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no TenantScopeNode");
    }

    @Test
    void wrongTenantIdFails() {
        assertThatThrownBy(() -> AstInvariants.assertTenantScopedAtRoot(new TenantScopeNode(7), 8))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("carries tenant 7");
    }

    @Test
    void duplicateTenantNodesFail() {
        AndExpr root = new AndExpr(new TenantScopeNode(7), new TenantScopeNode(7));
        assertThatThrownBy(() -> AstInvariants.assertTenantScopedAtRoot(root, 7))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("2 TenantScopeNode");
    }
}
