package com.manas.metaplatform.bookofrecord.web

import com.fasterxml.jackson.databind.ObjectMapper
import com.manas.metaplatform.bookofrecord.domain.LedgerEntryType
import com.manas.metaplatform.bookofrecord.restatement.RestatementDiff
import com.manas.metaplatform.bookofrecord.restatement.RestatementWriter
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional
import java.io.File
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * Exports a handful of real restatements (computed by the real [RestatementWriter] and
 * [RestatementDiff], not hand-typed) to `web/src/fixtures/restatements.json`, which is the
 * React console's data source (see `web/README.md`). This is part of the default
 * `mvn test` run so the fixture can never silently drift from what the backend actually
 * computes.
 */
@SpringBootTest
@Transactional
class ConsoleFixtureExporterTest {

    @Autowired
    private lateinit var restatementWriter: RestatementWriter

    @Test
    fun exportsRealRestatementsForTheReactConsole() {
        val diffs = listOf(
            buildDiff(
                "fact-console-1", "NVDA", LocalDate.of(2026, 7, 14),
                BigDecimal("240.000000"), BigDecimal("36000.000000"), BigDecimal("0.000000"),
                BigDecimal("260.000000"), BigDecimal("39000.000000"), BigDecimal("1500.000000"),
                Instant.parse("2026-07-15T00:00:00Z"), Instant.parse("2026-08-02T00:00:00Z"),
            ),
            buildDiff(
                "fact-console-2", "JPM", LocalDate.of(2026, 5, 3),
                BigDecimal("500.000000"), BigDecimal("75000.000000"), BigDecimal("2000.000000"),
                BigDecimal("480.000000"), BigDecimal("72000.000000"), BigDecimal("1700.000000"),
                Instant.parse("2026-05-04T00:00:00Z"), Instant.parse("2026-06-20T00:00:00Z"),
            ),
            buildDiff(
                "fact-console-3", "XOM", LocalDate.of(2026, 9, 1),
                BigDecimal("1000.000000"), BigDecimal("90000.000000"), BigDecimal("-500.000000"),
                BigDecimal("1000.000000"), BigDecimal("90000.000000"), BigDecimal("-500.000000"),
                Instant.parse("2026-09-02T00:00:00Z"), Instant.parse("2026-09-25T00:00:00Z"),
            ),
        )

        val mapper = ObjectMapper()
        val json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(diffs)

        val target = File("web/src/fixtures/restatements.json")
        target.parentFile.mkdirs()
        target.writeText(json)
    }

    private fun buildDiff(
        factId: String, symbol: String, effectiveDate: LocalDate,
        qty1: BigDecimal, cost1: BigDecimal, pnl1: BigDecimal,
        qty2: BigDecimal, cost2: BigDecimal, pnl2: BigDecimal,
        asOfFrom1: Instant, asOfFrom2: Instant,
    ): RestatementDiff {
        val original = restatementWriter.bookOriginal(
            1L, factId, symbol, effectiveDate, LedgerEntryType.TRADE, qty1, cost1, pnl1, asOfFrom1,
        )
        val corrected = restatementWriter.correct(factId, qty2, cost2, pnl2, asOfFrom2)
        return RestatementDiff.of(
            prior = original.also { it.closeAsOf(asOfFrom2) },
            restated = corrected,
        )
    }
}
