package com.manas.metaplatform.repository;

import com.manas.metaplatform.domain.Role;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RoleRepository extends JpaRepository<Role, Long> {

    List<Role> findByTenantId(Long tenantId);
}
