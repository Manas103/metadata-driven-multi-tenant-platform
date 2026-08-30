package com.manas.metaplatform.compiler;

import com.manas.metaplatform.dsl.ast.Expr;

import java.util.List;

/**
 * The common shape {@link SqlCompiler#compile} needs: a tenant id, a select list, an object
 * name, and an already fully-rewritten WHERE tree. {@link ScopedQuery} (tenant scoping only)
 * and {@code sharing.SharingScopedQuery} (tenant scoping plus the record-level sharing
 * filter) are the only two implementations, and each can only be constructed by the one
 * rewriter responsible for the invariant it carries. Widening {@link SqlCompiler#compile} to
 * accept this interface, rather than duplicating the code generator, is what lets the
 * sharing filter reuse exactly the same SQL emission {@code TenantScopingRewriter}'s output
 * always went through, instead of a second copy of it.
 */
public interface Scoped {

    long tenantId();

    List<String> selectFields();

    String objectName();

    /** The WHERE tree, always non-null and always tenant-scoped at the root at minimum. */
    Expr where();
}
