package com.manas.metaplatform.repository;

import com.manas.metaplatform.domain.RecordEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecordRepository extends JpaRepository<RecordEntity, Long> {
}
