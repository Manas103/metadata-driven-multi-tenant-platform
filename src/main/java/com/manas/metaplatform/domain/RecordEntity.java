package com.manas.metaplatform.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One generic record row: just a tenant id and an object definition id. This is the same
 * physical table for every tenant and every custom object; storing a new kind of record
 * never requires a new table or a new column, only a new {@link ObjectDefinition} row and,
 * for the record's actual field data, rows in {@link FieldValueEntity}.
 */
@Entity
@Table(name = "records")
public class RecordEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "object_definition_id", nullable = false)
    private Long objectDefinitionId;

    protected RecordEntity() {
    }

    public RecordEntity(Long tenantId, Long objectDefinitionId) {
        this.tenantId = tenantId;
        this.objectDefinitionId = objectDefinitionId;
    }

    public Long getId() {
        return id;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public Long getObjectDefinitionId() {
        return objectDefinitionId;
    }
}
