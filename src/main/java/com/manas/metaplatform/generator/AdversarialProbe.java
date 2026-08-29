package com.manas.metaplatform.generator;

/**
 * One adversarial cross-tenant probe: a query issued as {@code tenantId} ({@code tenantName})
 * against a shared object, built specifically to try to make the platform return a row that
 * belongs to a different tenant. {@code description} names the exact attack shape, so a
 * failure in the adversarial suite points straight at which strategy broke the guarantee.
 */
public record AdversarialProbe(long tenantId, String tenantName, String dsl, String description) {
}
