package com.manas.metaplatform.bookofrecord.repository;

import com.manas.metaplatform.bookofrecord.domain.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    List<LedgerEntry> findByFactIdOrderByVersionAsc(String factId);

    @Query("select e from LedgerEntry e where e.accountId = :accountId order by e.id asc")
    List<LedgerEntry> findAllByAccount(Long accountId);

    /**
     * The fast path's "current version" query: trusts the maintained {@code asOfTo}
     * pointer rather than recomputing it. This is the query
     * {@code PositionProjector} runs; {@code IndependentRecomputation} (Kotlin) computes
     * the same notion of "current" a structurally different way, from {@code asOfFrom}
     * alone, and the two are diffed.
     */
    @Query("select e from LedgerEntry e where e.asOfTo is null and e.effectiveDate <= :asOfDate")
    List<LedgerEntry> findCurrentAsOf(java.time.LocalDate asOfDate);

    @Query("select count(e) from LedgerEntry e")
    long countAll();
}
