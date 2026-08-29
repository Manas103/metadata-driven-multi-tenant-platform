package com.manas.metaplatform.generator;

import com.manas.metaplatform.domain.FieldDefinition;
import com.manas.metaplatform.domain.FieldType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Generates syntactically valid, randomly shaped DSL queries against a {@link SeedDataset}:
 * a random tenant, a random shared object it owns, one to three SELECT fields, and an
 * optional WHERE tree (a single comparison, an AND of two, or an OR of two) built from
 * literal values sampled from that tenant's own seeded data so every generated predicate
 * is type-correct for the field it names. This is the corpus the reference-translator
 * diff run and the main compiler are both driven from.
 */
@Component
public final class QueryGenerator {

    public List<GeneratedQuery> generate(SeedDataset dataset, int count, long seed) {
        Random random = new Random(seed);
        List<GeneratedQuery> queries = new ArrayList<>(count);
        List<String> tenantNames = dataset.tenantNames();
        for (int i = 0; i < count; i++) {
            String tenantName = tenantNames.get(random.nextInt(tenantNames.size()));
            long tenantId = dataset.tenantIdByName().get(tenantName);
            String objectName = dataset.sharedObjectNames().get(random.nextInt(dataset.sharedObjectNames().size()));
            long objectDefId = dataset.objectDefIdByTenantAndObject().get(tenantName).get(objectName);
            Map<String, FieldDefinition> fields = dataset.fieldDefsByObjectDefId().get(objectDefId);
            List<String> fieldNames = List.copyOf(fields.keySet());

            List<String> selectFields = pickSelectFields(fieldNames, random);
            String where = maybeBuildWhere(dataset, objectDefId, fields, fieldNames, random);

            String dsl = "SELECT " + String.join(", ", selectFields) + " FROM " + objectName
                    + (where.isEmpty() ? "" : " WHERE " + where);
            queries.add(new GeneratedQuery(tenantId, tenantName, dsl));
        }
        return queries;
    }

    private List<String> pickSelectFields(List<String> fieldNames, Random random) {
        int n = 1 + random.nextInt(Math.min(3, fieldNames.size()));
        List<String> shuffled = new ArrayList<>(fieldNames);
        java.util.Collections.shuffle(shuffled, random);
        return shuffled.subList(0, n);
    }

    private String maybeBuildWhere(SeedDataset dataset, long objectDefId, Map<String, FieldDefinition> fields,
                                    List<String> fieldNames, Random random) {
        int shape = random.nextInt(4); // 0: no WHERE, 1: single, 2: AND, 3: OR
        if (shape == 0) {
            return "";
        }
        String c1 = buildComparison(dataset, objectDefId, fields, fieldNames, random);
        if (shape == 1) {
            return c1;
        }
        String c2 = buildComparison(dataset, objectDefId, fields, fieldNames, random);
        return shape == 2 ? "(" + c1 + " AND " + c2 + ")" : "(" + c1 + " OR " + c2 + ")";
    }

    private String buildComparison(SeedDataset dataset, long objectDefId, Map<String, FieldDefinition> fields,
                                    List<String> fieldNames, Random random) {
        String fieldName = fieldNames.get(random.nextInt(fieldNames.size()));
        FieldType type = fields.get(fieldName).getFieldType();
        List<Object> samples = dataset.sampleValuesByObjectDefId().get(objectDefId).get(fieldName);
        Object sample = (samples == null || samples.isEmpty())
                ? defaultValue(type)
                : samples.get(random.nextInt(samples.size()));
        String operator = pickOperator(type, random);
        return fieldName + " " + operator + " " + literalText(type, sample, operator);
    }

    private Object defaultValue(FieldType type) {
        return switch (type) {
            case STRING -> "unset";
            case NUMBER -> java.math.BigDecimal.ONE;
            case BOOLEAN -> Boolean.TRUE;
        };
    }

    private String pickOperator(FieldType type, Random random) {
        return switch (type) {
            case STRING -> random.nextBoolean() ? "=" : "!=";
            case NUMBER -> switch (random.nextInt(6)) {
                case 0 -> "=";
                case 1 -> "!=";
                case 2 -> ">";
                case 3 -> ">=";
                case 4 -> "<";
                default -> "<=";
            };
            case BOOLEAN -> random.nextBoolean() ? "=" : "!=";
        };
    }

    private String literalText(FieldType type, Object value, String operator) {
        return switch (type) {
            case STRING -> "'" + value.toString().replace("'", "''") + "'";
            case NUMBER -> value.toString();
            case BOOLEAN -> value.toString().toUpperCase(java.util.Locale.ROOT);
        };
    }
}
