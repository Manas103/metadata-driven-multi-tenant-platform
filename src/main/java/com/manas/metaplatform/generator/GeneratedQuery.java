package com.manas.metaplatform.generator;

/** A syntactically valid, randomly generated DSL query paired with the tenant that issues it. */
public record GeneratedQuery(long tenantId, String tenantName, String dsl) {
}
