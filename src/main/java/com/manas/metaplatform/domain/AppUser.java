package com.manas.metaplatform.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A human user of a tenant, distinct from {@link Tenant} itself: a tenant is the account,
 * a user is one of the people inside it who owns records and has a place in the tenant's
 * {@link Role} hierarchy. Named {@code AppUser} rather than {@code User} to avoid colliding
 * with any framework or SQL-reserved-word notion of "user"; nothing here is a login
 * principal, only the identity the record-level sharing filter reasons about.
 */
@Entity
@Table(name = "app_users")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "role_id", nullable = false)
    private Long roleId;

    protected AppUser() {
    }

    public AppUser(Long tenantId, String name, Long roleId) {
        this.tenantId = tenantId;
        this.name = name;
        this.roleId = roleId;
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

    public Long getRoleId() {
        return roleId;
    }
}
