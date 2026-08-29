package com.manas.metaplatform.repository;

import com.manas.metaplatform.domain.ObjectDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ObjectDefinitionRepository extends JpaRepository<ObjectDefinition, Long> {

    Optional<ObjectDefinition> findByTenantIdAndName(Long tenantId, String name);

    List<ObjectDefinition> findByTenantId(Long tenantId);
}
