package com.manas.metaplatform.generator;

import com.manas.metaplatform.domain.FieldDefinition;
import com.manas.metaplatform.domain.FieldType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Builds queries deliberately shaped to try to leak another tenant's rows across the
 * structural tenant-scoping guarantee ({@code compiler.TenantScopingRewriter} /
 * {@code compiler.AstInvariants}). Two attack shapes, split evenly across the requested
 * count:
 *
 * <ul>
 *   <li><b>Cross-tenant literal collision.</b> Tenant A queries a shared object using a
 *       literal value sampled from Tenant B's actual seeded data for that field (a name, an
 *       email, an exact amount). If tenant scoping were ever appended as an afterthought
 *       instead of structurally injected, a permissive WHERE-clause bug could let this
 *       literal match Tenant B's row. The compiler is expected to return 0 rows or only
 *       Tenant A's own coincidentally-matching rows, never a Tenant B row.</li>
 *   <li><b>Parameterization-breaking literal.</b> A string literal built to look like a SQL
 *       injection payload ({@code x' OR '1'='1}, {@code '; DROP TABLE records; --}), passed
 *       through the DSL's own escaping (doubled quotes) so it parses as one legal string
 *       literal token, then bound as a single {@code ?} parameter. Because every literal
 *       travels through {@link com.manas.metaplatform.compiler.CompiledSql#params()} rather
 *       than through string concatenation, this attack has no SQL syntax to break out of;
 *       the probe checks that outcome by construction rather than by inspecting the
 *       generated SQL text.</li>
 * </ul>
 */
@Component
public final class AdversarialQueryGenerator {

    public List<AdversarialProbe> generate(SeedDataset dataset, int count, long seed) {
        Random random = new Random(seed);
        List<AdversarialProbe> probes = new ArrayList<>(count);
        int half = count / 2;
        for (int i = 0; i < half; i++) {
            probes.add(crossTenantLiteralProbe(dataset, random));
        }
        for (int i = half; i < count; i++) {
            probes.add(injectionLiteralProbe(dataset, random));
        }
        return probes;
    }

    private AdversarialProbe crossTenantLiteralProbe(SeedDataset dataset, Random random) {
        List<String> tenantNames = dataset.tenantNames();
        String attackerName = tenantNames.get(random.nextInt(tenantNames.size()));
        String victimName;
        do {
            victimName = tenantNames.get(random.nextInt(tenantNames.size()));
        } while (victimName.equals(attackerName));

        long attackerId = dataset.tenantIdByName().get(attackerName);
        String objectName = dataset.sharedObjectNames().get(random.nextInt(dataset.sharedObjectNames().size()));
        long victimObjectDefId = dataset.objectDefIdByTenantAndObject().get(victimName).get(objectName);
        long attackerObjectDefId = dataset.objectDefIdByTenantAndObject().get(attackerName).get(objectName);

        Map<String, FieldDefinition> attackerFields = dataset.fieldDefsByObjectDefId().get(attackerObjectDefId);
        List<String> fieldNames = List.copyOf(attackerFields.keySet());
        String fieldName = fieldNames.get(random.nextInt(fieldNames.size()));
        FieldType type = attackerFields.get(fieldName).getFieldType();

        List<Object> victimSamples = dataset.sampleValuesByObjectDefId().get(victimObjectDefId).get(fieldName);
        Object victimValue = (victimSamples == null || victimSamples.isEmpty())
                ? defaultValue(type) : victimSamples.get(random.nextInt(victimSamples.size()));

        String dsl = "SELECT " + fieldName + " FROM " + objectName + " WHERE " + fieldName + " = "
                + literalText(type, victimValue);
        return new AdversarialProbe(attackerId, attackerName, dsl,
                "cross-tenant literal collision: " + attackerName + " probing for " + victimName
                        + "'s " + fieldName + " value on shared object " + objectName);
    }

    private AdversarialProbe injectionLiteralProbe(SeedDataset dataset, Random random) {
        List<String> tenantNames = dataset.tenantNames();
        String attackerName = tenantNames.get(random.nextInt(tenantNames.size()));
        long attackerId = dataset.tenantIdByName().get(attackerName);
        String objectName = dataset.sharedObjectNames().get(random.nextInt(dataset.sharedObjectNames().size()));
        long attackerObjectDefId = dataset.objectDefIdByTenantAndObject().get(attackerName).get(objectName);
        Map<String, FieldDefinition> fields = dataset.fieldDefsByObjectDefId().get(attackerObjectDefId);
        List<String> stringFields = fields.values().stream()
                .filter(fd -> fd.getFieldType() == FieldType.STRING)
                .map(FieldDefinition::getName)
                .toList();
        String fieldName = stringFields.isEmpty()
                ? List.copyOf(fields.keySet()).get(0)
                : stringFields.get(random.nextInt(stringFields.size()));

        String[] payloads = {
                "x' OR '1'='1",
                "'; DROP TABLE records; --",
                "' OR tenant_id != tenant_id OR '",
                "anything' UNION SELECT * FROM field_values --",
        };
        String payload = payloads[random.nextInt(payloads.length)];
        String dsl = "SELECT " + fieldName + " FROM " + objectName + " WHERE " + fieldName + " = "
                + literalText(FieldType.STRING, payload);
        return new AdversarialProbe(attackerId, attackerName, dsl,
                "parameterization-breaking literal on " + objectName + "." + fieldName + ": " + payload);
    }

    private Object defaultValue(FieldType type) {
        return switch (type) {
            case STRING -> "unset";
            case NUMBER -> java.math.BigDecimal.ONE;
            case BOOLEAN -> Boolean.TRUE;
        };
    }

    private String literalText(FieldType type, Object value) {
        return switch (type) {
            case STRING -> "'" + value.toString().replace("'", "''") + "'";
            case NUMBER -> value.toString();
            case BOOLEAN -> value.toString().toUpperCase(Locale.ROOT);
        };
    }
}
