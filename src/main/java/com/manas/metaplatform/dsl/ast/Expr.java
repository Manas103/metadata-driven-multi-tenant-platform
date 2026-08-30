package com.manas.metaplatform.dsl.ast;

/**
 * A node in the WHERE-clause syntax tree. Sealed to exactly the five shapes this DSL needs:
 * a leaf comparison, a boolean AND/OR of two sub-expressions, {@link TenantScopeNode}, the
 * node the tenant-scoping rewriter injects, and {@link SharingScopeNode}, the node the
 * record-level sharing rewriter injects alongside it. Both scope nodes are their own
 * distinct case rather than a disguised {@link Comparison} specifically so that
 * {@code compiler.AstInvariants} / {@code sharing.SharingAstInvariants} can walk a tree and
 * identify "is the tenant predicate present" / "is the sharing predicate present"
 * structurally, by type, instead of by pattern-matching a field name.
 */
public sealed interface Expr permits Comparison, AndExpr, OrExpr, TenantScopeNode, SharingScopeNode {
}
