package com.manas.metaplatform.bookofrecord.restatement;

import com.manas.metaplatform.bookofrecord.domain.LedgerEntry;
import com.manas.metaplatform.bookofrecord.domain.LedgerEntryType;
import com.manas.metaplatform.bookofrecord.repository.LedgerEntryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the one invariant the whole bitemporal design rests on: a correction never
 * runs {@code UPDATE} on an existing row's economic fields. It only closes
 * {@code as_of_to} on the prior version and inserts a brand new row.
 */
@SpringBootTest
@Transactional
class RestatementWriterTest {

    @Autowired
    private RestatementWriter restatementWriter;
    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Test
    void correctionNeverMutatesThePriorVersionsEconomicFields() {
        Instant bookedAt = Instant.parse("2026-09-01T00:00:00Z");
        LocalDate effectiveDate = LocalDate.of(2026, 8, 15);
        LedgerEntry original = restatementWriter.bookOriginal(
                1L, "fact-write-test-1", "AAPL", effectiveDate, LedgerEntryType.TRADE,
                new BigDecimal("100.000000"), new BigDecimal("15000.000000"), new BigDecimal("0.000000"),
                bookedAt);

        BigDecimal originalQuantity = original.getQuantity();
        BigDecimal originalCostBasis = original.getCostBasis();
        BigDecimal originalPnl = original.getRealizedPnl();
        LocalDate originalEffectiveDate = original.getEffectiveDate();
        assertThat(original.getAsOfTo()).isNull();

        Instant correctionAt = Instant.parse("2026-09-20T00:00:00Z");
        LedgerEntry corrected = restatementWriter.correct(
                "fact-write-test-1", new BigDecimal("120.000000"), new BigDecimal("16000.000000"),
                new BigDecimal("50.000000"), correctionAt);

        LedgerEntry reloadedOriginal = ledgerEntryRepository.findById(original.getId()).orElseThrow();

        // The prior version's economic fields are byte-identical: no UPDATE touched them.
        assertThat(reloadedOriginal.getQuantity()).isEqualByComparingTo(originalQuantity);
        assertThat(reloadedOriginal.getCostBasis()).isEqualByComparingTo(originalCostBasis);
        assertThat(reloadedOriginal.getRealizedPnl()).isEqualByComparingTo(originalPnl);
        assertThat(reloadedOriginal.getEffectiveDate()).isEqualTo(originalEffectiveDate);
        // Only the transaction-time bookkeeping field changed.
        assertThat(reloadedOriginal.getAsOfTo()).isEqualTo(correctionAt);

        // A brand new row was inserted, not a mutation of the old one.
        assertThat(corrected.getId()).isNotEqualTo(reloadedOriginal.getId());
        assertThat(corrected.getFactId()).isEqualTo(reloadedOriginal.getFactId());
        assertThat(corrected.getVersion()).isEqualTo(reloadedOriginal.getVersion() + 1);
        assertThat(corrected.getAsOfTo()).isNull();
        assertThat(corrected.getQuantity()).isEqualByComparingTo("120.000000");
        assertThat(corrected.getCostBasis()).isEqualByComparingTo("16000.000000");
        assertThat(corrected.getRealizedPnl()).isEqualByComparingTo("50.000000");

        List<LedgerEntry> versions = ledgerEntryRepository.findByFactIdOrderByVersionAsc("fact-write-test-1");
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).getId()).isEqualTo(reloadedOriginal.getId());
        assertThat(versions.get(1).getId()).isEqualTo(corrected.getId());
    }
}
