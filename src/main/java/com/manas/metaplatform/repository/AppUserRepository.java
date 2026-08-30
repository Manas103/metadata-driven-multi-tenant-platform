package com.manas.metaplatform.repository;

import com.manas.metaplatform.domain.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    List<AppUser> findByTenantId(Long tenantId);
}
