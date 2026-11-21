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

    @Query("select distinct e.factId from LedgerEntry e where e.accountId = :accountId and e.version > 1 order by e.factId")
    List<String> restatedFactIds(Long accountId);

    /**
     * Bitemporal read: the account's entries effective on or before {@code asOfDate}, as the
     * system believed them at {@code believedAt} (the version whose transaction-time window
     * [asOfFrom, asOfTo) contains that instant), so a past answer stays reproducible after
     * later corrections.
     */
    @Query("""
            select e from LedgerEntry e
            where e.accountId = :accountId and e.effectiveDate <= :asOfDate
              and e.asOfFrom <= :believedAt and (e.asOfTo is null or e.asOfTo > :believedAt)
            order by e.factId""")
    List<LedgerEntry> believedAt(Long accountId, java.time.LocalDate asOfDate, java.time.Instant believedAt);
}
