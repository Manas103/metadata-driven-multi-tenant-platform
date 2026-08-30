package com.manas.metaplatform.generator;

/**
 * One randomly shaped, sharing-scoped query: which user is viewing, and the DSL text. Used by
 * the reference-translator diff run to check that {@code compiler.QueryExecutor#executeWithSharing}
 * and {@code reference.ReferenceInterpreter#executeWithSharing} agree row for row, the same way
 * {@link GeneratedQuery} drives the tenant-only diff.
 */
public record GeneratedSharingQuery(long tenantId, long viewerUserId, String viewerLabel, String dsl) {
}
