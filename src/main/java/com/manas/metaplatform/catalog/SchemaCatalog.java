package com.manas.metaplatform.catalog;

import com.manas.metaplatform.domain.FieldDefinition;
import com.manas.metaplatform.domain.ObjectDefinition;
import com.manas.metaplatform.repository.FieldDefinitionRepository;
import com.manas.metaplatform.repository.ObjectDefinitionRepository;
import org.springframework.stereotype.Component;

/**
 * The single place both the main compiler and the independent reference interpreter go to
 * resolve a DSL object/field name to metadata. Every lookup is scoped by tenant id (for
 * objects) or by the already-tenant-scoped object definition id (for fields), so an object
 * or field name that exists for another tenant but not the querying one resolves to
 * {@link UnknownObjectException} / {@link UnknownFieldException}, never to the other
 * tenant's definition. This class only reads metadata rows; it has no opinion about how a
 * query is lowered to SQL, which is why sharing it between the compiler and the reference
 * interpreter does not compromise their independence as a diff oracle.
 */
@Component
public class SchemaCatalog {

    private final ObjectDefinitionRepository objectDefinitionRepository;
    private final FieldDefinitionRepository fieldDefinitionRepository;

    public SchemaCatalog(ObjectDefinitionRepository objectDefinitionRepository,
                          FieldDefinitionRepository fieldDefinitionRepository) {
        this.objectDefinitionRepository = objectDefinitionRepository;
        this.fieldDefinitionRepository = fieldDefinitionRepository;
    }

    public ObjectDefinition resolveObject(long tenantId, String objectName) {
        return objectDefinitionRepository.findByTenantIdAndName(tenantId, objectName)
                .orElseThrow(() -> new UnknownObjectException(tenantId, objectName));
    }

    public FieldDefinition resolveField(long objectDefinitionId, String fieldName) {
        return fieldDefinitionRepository.findByObjectDefinitionIdAndName(objectDefinitionId, fieldName)
                .orElseThrow(() -> new UnknownFieldException(objectDefinitionId, fieldName));
    }
}
