package com.manas.metaplatform.generator;

import com.manas.metaplatform.domain.FieldDefinition;

import java.util.List;
import java.util.Map;

/**
 * Everything {@link QueryGenerator} and {@link AdversarialQueryGenerator} need to build
 * syntactically valid queries and adversarial probes against a seeded dataset, without
 * re-querying the database for catalog metadata on every generated query.
 */
public record SeedDataset(
        List<String> tenantNames,
        Map<String, Long> tenantIdByName,
        List<String> sharedObjectNames,
        Map<String, Map<String, Long>> objectDefIdByTenantAndObject,
        Map<Long, Map<String, FieldDefinition>> fieldDefsByObjectDefId,
        Map<Long, List<Long>> recordIdsByObjectDefId,
        Map<Long, Map<String, List<Object>>> sampleValuesByObjectDefId,
        Map<String, String> exclusiveObjectNameByTenant) {
}
