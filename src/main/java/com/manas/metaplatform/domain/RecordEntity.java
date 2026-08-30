package com.manas.metaplatform.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One generic record row: a tenant id, an object definition id, and an optional owning
 * user id. This is the same physical table for every tenant and every custom object;
 * storing a new kind of record never requires a new table or a new column, only a new
 * {@link ObjectDefinition} row and, for the record's actual field data, rows in
 * {@link FieldValueEntity}. {@code ownerUserId} is nullable and unset by every record the
 * original tenant-scoping dataset ({@code generator.DataSeeder}) creates, since ownership is
 * meaningless to the tenant-isolation claim; only the sharing-filter dataset
 * ({@code generator.SharingDataSeeder}) populates it, which is why the two-argument
 * constructor those original callers already use is left untouched below.
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

    @Column(name = "owner_user_id")
    private Long ownerUserId;

    protected RecordEntity() {
    }

    public RecordEntity(Long tenantId, Long objectDefinitionId) {
        this(tenantId, objectDefinitionId, null);
    }

    public RecordEntity(Long tenantId, Long objectDefinitionId, Long ownerUserId) {
        this.tenantId = tenantId;
        this.objectDefinitionId = objectDefinitionId;
        this.ownerUserId = ownerUserId;
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

    public Long getOwnerUserId() {
        return ownerUserId;
    }
}
