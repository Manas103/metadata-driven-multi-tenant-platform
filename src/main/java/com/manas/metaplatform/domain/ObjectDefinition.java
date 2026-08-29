package com.manas.metaplatform.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * A tenant-defined "custom object": nothing more than a name scoped to a tenant. Two
 * different tenants may (and in this repository's seeded data, deliberately do) declare
 * an object with the identical name, e.g. both declare "Contact"; they resolve to two
 * distinct rows here with two distinct ids and two independent sets of
 * {@link FieldDefinition}s, which is exactly the case the adversarial cross-tenant suite
 * targets.
 */
@Entity
@Table(name = "object_definitions",
        uniqueConstraints = @UniqueConstraint(columnNames = {"tenant_id", "name"}))
public class ObjectDefinition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(nullable = false, length = 200)
    private String name;

    protected ObjectDefinition() {
    }

    public ObjectDefinition(Long tenantId, String name) {
        this.tenantId = tenantId;
        this.name = name;
    }

    public Long getId() {
        return id;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public String getName() {
        return name;
    }
}
