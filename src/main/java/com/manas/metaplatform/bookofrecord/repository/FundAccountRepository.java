package com.manas.metaplatform.bookofrecord.repository;

import com.manas.metaplatform.bookofrecord.domain.FundAccount;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FundAccountRepository extends JpaRepository<FundAccount, Long> {
}
