package com.manas.metaplatform.dsl.ast;

/**
 * The tenant predicate, as an AST node in its own right rather than a string appended
 * anywhere. Produced exactly once per query, exclusively by
 * {@code compiler.TenantScopingRewriter}, and ANDed at the root of the WHERE tree before
 * the query is ever handed to the SQL code generator. There is no {@code fieldName} here
 * because this node does not compile through the field/join machinery at all; the code
 * generator lowers it directly to {@code r.tenant_id = ?} against the physical records
 * table, so it can never be spoofed by a DSL field named "tenant_id" (the DSL has no such
 * field in the seeded catalog, and even if it did, this node type is what the generator
 * dispatches on, not the field name).
 */
public record TenantScopeNode(long tenantId) implements Expr {
}
