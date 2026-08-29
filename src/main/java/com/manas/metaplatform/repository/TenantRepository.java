package com.manas.metaplatform.repository;

import com.manas.metaplatform.domain.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantRepository extends JpaRepository<Tenant, Long> {
}
