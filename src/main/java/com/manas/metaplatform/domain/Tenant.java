package com.manas.metaplatform.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * A tenant. Nothing about a tenant provisions any database object; on-boarding a tenant
 * is one row in this table plus, later, rows in {@link ObjectDefinition} and
 * {@link FieldDefinition} as it defines its own objects and fields. No DDL is ever run
 * for a tenant's schema.
 */
@Entity
@Table(name = "tenants", uniqueConstraints = @UniqueConstraint(columnNames = "name"))
public class Tenant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    protected Tenant() {
    }

    public Tenant(String name) {
        this.name = name;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }
}
