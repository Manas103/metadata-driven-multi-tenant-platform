package com.manas.metaplatform.bookofrecord.restatement;

import com.manas.metaplatform.bookofrecord.domain.LedgerEntry;
import com.manas.metaplatform.bookofrecord.domain.LedgerEntryType;
import com.manas.metaplatform.bookofrecord.repository.LedgerEntryRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * The one and only place a correction is applied. A back-dated trade or corporate-action
 * correction never runs {@code UPDATE} on an existing row's economic fields: it closes the
 * prior version's {@code as_of_to} (a transaction-time bookkeeping field, not an economic
 * one) and inserts a brand new row carrying the same {@code fact_id} and the corrected
 * {@code quantity}/{@code cost_basis}/{@code realized_pnl}. History is never mutated;
 * {@link RestatementWriterTest} asserts the prior row's economic fields are byte-identical
 * before and after a correction.
 */
@Component
public class RestatementWriter {

    private final LedgerEntryRepository ledgerEntryRepository;

    public RestatementWriter(LedgerEntryRepository ledgerEntryRepository) {
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    public LedgerEntry bookOriginal(Long accountId, String factId, String symbol, LocalDate effectiveDate,
                                     LedgerEntryType entryType, BigDecimal quantity, BigDecimal costBasis,
                                     BigDecimal realizedPnl, Instant asOfFrom) {
        LedgerEntry entry = new LedgerEntry(accountId, factId, 1, symbol, effectiveDate, entryType,
                quantity, costBasis, realizedPnl, asOfFrom);
        return ledgerEntryRepository.save(entry);
    }

    /**
     * Applies a correction to {@code factId}. The currently-open version (the one with
     * {@code asOfTo == null}) is closed at {@code correctionAsOfFrom}; a new version with
     * the corrected economic fields is inserted, open from that same instant.
     */
    @Transactional
    public LedgerEntry correct(String factId, BigDecimal correctedQuantity, BigDecimal correctedCostBasis,
                                BigDecimal correctedRealizedPnl, Instant correctionAsOfFrom) {
        List<LedgerEntry> versions = ledgerEntryRepository.findByFactIdOrderByVersionAsc(factId);
        LedgerEntry openVersion = versions.stream()
                .filter(v -> v.getAsOfTo() == null)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No open version for fact " + factId));

        openVersion.closeAsOf(correctionAsOfFrom);
        ledgerEntryRepository.save(openVersion);

        LedgerEntry corrected = new LedgerEntry(
                openVersion.getAccountId(), factId, openVersion.getVersion() + 1, openVersion.getSymbol(),
                openVersion.getEffectiveDate(), openVersion.getEntryType(),
                correctedQuantity, correctedCostBasis, correctedRealizedPnl, correctionAsOfFrom);
        return ledgerEntryRepository.save(corrected);
    }
}
