package com.manas.metaplatform.generator;

import com.manas.metaplatform.domain.FieldDefinition;
import com.manas.metaplatform.domain.FieldType;
import com.manas.metaplatform.domain.FieldValueEntity;
import com.manas.metaplatform.domain.ObjectDefinition;
import com.manas.metaplatform.domain.RecordEntity;
import com.manas.metaplatform.domain.Tenant;
import com.manas.metaplatform.repository.FieldDefinitionRepository;
import com.manas.metaplatform.repository.FieldValueRepository;
import com.manas.metaplatform.repository.ObjectDefinitionRepository;
import com.manas.metaplatform.repository.RecordRepository;
import com.manas.metaplatform.repository.TenantRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Builds a deterministic, seeded multi-tenant dataset: every tenant declares the same
 * three shared object names with the same field schema (deliberately, so those names
 * collide across tenants at the metadata level) plus one object exclusive to itself, then
 * populates records with a mix of present and deliberately-absent field values so the
 * EAV storage's sparse rows and LEFT JOIN/NULL semantics are actually exercised by the
 * generated query corpus, not just the happy path.
 */
@Component
public final class DataSeeder {

    private static final List<String> TENANT_NAMES =
            List.of("Acme", "Globex", "Initech", "Umbrella", "Stark", "Wayne");

    private static final Map<String, List<FieldSpec>> SHARED_OBJECTS = Map.of(
            "Contact", List.of(
                    new FieldSpec("name", FieldType.STRING),
                    new FieldSpec("age", FieldType.NUMBER),
                    new FieldSpec("active", FieldType.BOOLEAN),
                    new FieldSpec("email", FieldType.STRING)),
            "Deal", List.of(
                    new FieldSpec("title", FieldType.STRING),
                    new FieldSpec("amount", FieldType.NUMBER),
                    new FieldSpec("closed", FieldType.BOOLEAN)),
            "Task", List.of(
                    new FieldSpec("subject", FieldType.STRING),
                    new FieldSpec("priority", FieldType.NUMBER),
                    new FieldSpec("done", FieldType.BOOLEAN)));

    private static final int RECORDS_PER_SHARED_OBJECT = 150;
    private static final int RECORDS_PER_EXCLUSIVE_OBJECT = 20;
    private static final double FIELD_PRESENCE_RATE = 0.85;

    private final TenantRepository tenantRepository;
    private final ObjectDefinitionRepository objectDefinitionRepository;
    private final FieldDefinitionRepository fieldDefinitionRepository;
    private final RecordRepository recordRepository;
    private final FieldValueRepository fieldValueRepository;

    public DataSeeder(TenantRepository tenantRepository,
                       ObjectDefinitionRepository objectDefinitionRepository,
                       FieldDefinitionRepository fieldDefinitionRepository,
                       RecordRepository recordRepository,
                       FieldValueRepository fieldValueRepository) {
        this.tenantRepository = tenantRepository;
        this.objectDefinitionRepository = objectDefinitionRepository;
        this.fieldDefinitionRepository = fieldDefinitionRepository;
        this.recordRepository = recordRepository;
        this.fieldValueRepository = fieldValueRepository;
    }

    public SeedDataset seed(long randomSeed) {
        Random random = new Random(randomSeed);

        Map<String, Long> tenantIdByName = new LinkedHashMap<>();
        for (String name : TENANT_NAMES) {
            Tenant tenant = tenantRepository.save(new Tenant(name));
            tenantIdByName.put(name, tenant.getId());
        }

        Map<String, Map<String, Long>> objectDefIdByTenantAndObject = new LinkedHashMap<>();
        Map<Long, Map<String, FieldDefinition>> fieldDefsByObjectDefId = new LinkedHashMap<>();
        Map<Long, List<Long>> recordIdsByObjectDefId = new LinkedHashMap<>();
        Map<Long, Map<String, List<Object>>> sampleValuesByObjectDefId = new LinkedHashMap<>();
        Map<String, String> exclusiveObjectNameByTenant = new LinkedHashMap<>();

        for (String tenantName : TENANT_NAMES) {
            long tenantId = tenantIdByName.get(tenantName);
            Map<String, Long> objectsForTenant = new LinkedHashMap<>();

            for (Map.Entry<String, List<FieldSpec>> objectEntry : SHARED_OBJECTS.entrySet()) {
                long objectDefId = createObjectWithFields(tenantId, objectEntry.getKey(), objectEntry.getValue(),
                        fieldDefsByObjectDefId);
                objectsForTenant.put(objectEntry.getKey(), objectDefId);
                populateRecords(tenantId, objectDefId, fieldDefsByObjectDefId.get(objectDefId),
                        RECORDS_PER_SHARED_OBJECT, random, recordIdsByObjectDefId, sampleValuesByObjectDefId,
                        tenantName + "-" + objectEntry.getKey());
            }

            String exclusiveName = tenantName + "Secret";
            long exclusiveObjectDefId = createObjectWithFields(tenantId, exclusiveName,
                    List.of(new FieldSpec("codeword", FieldType.STRING),
                            new FieldSpec("clearanceLevel", FieldType.NUMBER)),
                    fieldDefsByObjectDefId);
            objectsForTenant.put(exclusiveName, exclusiveObjectDefId);
            exclusiveObjectNameByTenant.put(tenantName, exclusiveName);
            populateRecords(tenantId, exclusiveObjectDefId, fieldDefsByObjectDefId.get(exclusiveObjectDefId),
                    RECORDS_PER_EXCLUSIVE_OBJECT, random, recordIdsByObjectDefId, sampleValuesByObjectDefId,
                    tenantName + "-" + exclusiveName);

            objectDefIdByTenantAndObject.put(tenantName, objectsForTenant);
        }

        return new SeedDataset(
                TENANT_NAMES,
                tenantIdByName,
                List.copyOf(SHARED_OBJECTS.keySet()),
                objectDefIdByTenantAndObject,
                fieldDefsByObjectDefId,
                recordIdsByObjectDefId,
                sampleValuesByObjectDefId,
                exclusiveObjectNameByTenant);
    }

    private long createObjectWithFields(long tenantId, String objectName, List<FieldSpec> fields,
                                         Map<Long, Map<String, FieldDefinition>> fieldDefsByObjectDefId) {
        ObjectDefinition objectDefinition = objectDefinitionRepository.save(new ObjectDefinition(tenantId, objectName));
        Map<String, FieldDefinition> fieldMap = new LinkedHashMap<>();
        for (FieldSpec spec : fields) {
            FieldDefinition fd = fieldDefinitionRepository.save(
                    new FieldDefinition(objectDefinition.getId(), spec.name(), spec.type()));
            fieldMap.put(spec.name(), fd);
        }
        fieldDefsByObjectDefId.put(objectDefinition.getId(), fieldMap);
        return objectDefinition.getId();
    }

    private void populateRecords(long tenantId, long objectDefId, Map<String, FieldDefinition> fields,
                                  int count, Random random,
                                  Map<Long, List<Long>> recordIdsByObjectDefId,
                                  Map<Long, Map<String, List<Object>>> sampleValuesByObjectDefId,
                                  String labelPrefix) {
        List<Long> recordIds = new ArrayList<>(count);
        Map<String, List<Object>> samples = new LinkedHashMap<>();
        for (String fieldName : fields.keySet()) {
            samples.put(fieldName, new ArrayList<>());
        }

        for (int i = 0; i < count; i++) {
            RecordEntity record = recordRepository.save(new RecordEntity(tenantId, objectDefId));
            recordIds.add(record.getId());

            for (FieldDefinition fd : fields.values()) {
                if (random.nextDouble() >= FIELD_PRESENCE_RATE) {
                    continue; // deliberately sparse: this field is unset on this record
                }
                Object value = randomValue(fd.getFieldType(), random, labelPrefix, fd.getName(), i);
                FieldValueEntity fv = switch (fd.getFieldType()) {
                    case STRING -> FieldValueEntity.ofString(record.getId(), fd.getId(), (String) value);
                    case NUMBER -> FieldValueEntity.ofNumber(record.getId(), fd.getId(), (BigDecimal) value);
                    case BOOLEAN -> FieldValueEntity.ofBoolean(record.getId(), fd.getId(), (Boolean) value);
                };
                fieldValueRepository.save(fv);
                List<Object> fieldSamples = samples.get(fd.getName());
                if (fieldSamples.size() < 25) {
                    fieldSamples.add(value);
                }
            }
        }
        recordIdsByObjectDefId.put(objectDefId, recordIds);
        sampleValuesByObjectDefId.put(objectDefId, samples);
    }

    private Object randomValue(FieldType type, Random random, String labelPrefix, String fieldName, int index) {
        return switch (type) {
            case STRING -> labelPrefix + "-" + fieldName + "-" + index + "-" + random.nextInt(100000);
            case NUMBER -> BigDecimal.valueOf(random.nextInt(100000), 2);
            case BOOLEAN -> random.nextBoolean();
        };
    }

    private record FieldSpec(String name, FieldType type) {
    }
}
