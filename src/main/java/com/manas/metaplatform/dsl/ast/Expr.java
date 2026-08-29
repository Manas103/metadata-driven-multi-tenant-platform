package com.manas.metaplatform.dsl.ast;

/**
 * A node in the WHERE-clause syntax tree. Sealed to exactly the four shapes this DSL needs:
 * a leaf comparison, a boolean AND/OR of two sub-expressions, and {@link TenantScopeNode},
 * the node the tenant-scoping rewriter injects. TenantScopeNode is its own distinct case
 * rather than a disguised {@link Comparison} specifically so that
 * {@code compiler.AstInvariants} can walk a tree and identify "is the tenant predicate
 * present" structurally, by type, instead of by pattern-matching a field name.
 */
public sealed interface Expr permits Comparison, AndExpr, OrExpr, TenantScopeNode {
}
