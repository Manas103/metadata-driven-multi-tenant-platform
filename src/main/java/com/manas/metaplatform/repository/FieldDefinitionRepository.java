package com.manas.metaplatform.repository;

import com.manas.metaplatform.domain.FieldDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FieldDefinitionRepository extends JpaRepository<FieldDefinition, Long> {

    Optional<FieldDefinition> findByObjectDefinitionIdAndName(Long objectDefinitionId, String name);

    List<FieldDefinition> findByObjectDefinitionId(Long objectDefinitionId);
}
