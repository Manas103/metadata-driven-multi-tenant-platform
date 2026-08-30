package com.manas.metaplatform.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One position in a tenant's role hierarchy: a name and an optional {@code parentRoleId}
 * pointing at the role above it (its manager's role). A role with no parent is the top of
 * its tenant's hierarchy. This table only describes the shape of the hierarchy; who actually
 * reports where is {@link AppUser#getRoleId()}. {@code sharing.RoleHierarchyResolver} walks
 * this tree, in Java, to decide which owners' records a given viewer may see through the
 * hierarchy, the same "small in-memory graph, no recursive SQL" approach the rest of this
 * project already uses for catalog metadata.
 */
@Entity
@Table(name = "roles")
public class Role {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "parent_role_id")
    private Long parentRoleId;

    protected Role() {
    }

    public Role(Long tenantId, String name, Long parentRoleId) {
        this.tenantId = tenantId;
        this.name = name;
        this.parentRoleId = parentRoleId;
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

    public Long getParentRoleId() {
        return parentRoleId;
    }
}
