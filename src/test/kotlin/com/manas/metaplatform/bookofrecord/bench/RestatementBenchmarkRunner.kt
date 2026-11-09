package com.manas.metaplatform.bookofrecord.bench

import com.manas.metaplatform.bookofrecord.generator.BookOfRecordDataSeeder
import com.manas.metaplatform.bookofrecord.oracle.IndependentRecomputation
import com.manas.metaplatform.bookofrecord.projection.PositionProjector
import com.manas.metaplatform.bookofrecord.repository.LedgerEntryRepository
import com.manas.metaplatform.domain.Tenant
import com.manas.metaplatform.repository.TenantRepository
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import kotlin.system.measureTimeMillis
import kotlin.test.assertEquals

/**
 * The real measurement run behind the as-of restatement resume claim. Not part of the
 * default `mvn test` run (tagged `benchmark`); run explicitly with
 * `mvn test -Dtest=RestatementBenchmarkRunner -Dsurefire.excludedGroups=`.
 *
 * Seeds 500 accounts / 250,000 ledger rows (50 corrected facts per account, 25,000 total),
 * then diffs the fast path ([PositionProjector], SQL GROUP BY trusting `as_of_to`) against
 * the independent oracle ([IndependentRecomputation], raw JDBC, decides "current" from
 * `as_of_from` alone) on every one of the 250,000 rows' current/superseded verdict, and on
 * every account/symbol's summed position.
 */
@SpringBootTest
@Transactional
@Tag("benchmark")
class RestatementBenchmarkRunner {

    @Autowired
    private lateinit var tenantRepository: TenantRepository
    @Autowired
    private lateinit var seeder: BookOfRecordDataSeeder
    @Autowired
    private lateinit var positionProjector: PositionProjector
    @Autowired
    private lateinit var oracle: IndependentRecomputation
    @Autowired
    private lateinit var ledgerEntryRepository: LedgerEntryRepository

    @Test
    fun restatementAgreesWithIndependentRecomputationAcross250kRows() {
        val log = StringBuilder()
        val tenant = tenantRepository.save(Tenant("ArcesiumBookOfRecordBenchmark"))

        val seedMillis = measureTimeMillis {
            val result = seeder.seed(tenant.id, 9001L)
            assertEquals(BookOfRecordDataSeeder.TOTAL_ROWS, result.insertedRows)
            log.append("Seeded ${result.insertedRows} ledger rows across ${BookOfRecordDataSeeder.ACCOUNT_COUNT} accounts, ")
                .append("${result.correctedFactCount} corrected facts\n")
        }
        log.append("Seed time: ${seedMillis}ms\n\n")

        val asOfDate = LocalDate.of(2026, 11, 1)

        var fastVerdicts: Set<Long>
        val fastMillis = measureTimeMillis {
            fastVerdicts = ledgerEntryRepository.findCurrentAsOf(asOfDate).map { it.id }.toSet()
        }

        var oracleVerdicts: Map<Long, Boolean>
        val oracleMillis = measureTimeMillis {
            oracleVerdicts = oracle.computeCurrentVerdicts()
        }

        val allRowIds = mutableListOf<Long>()
        var mismatches = 0
        val mismatchSamples = mutableListOf<String>()
        ledgerEntryRepository.findAll().forEach { row ->
            allRowIds.add(row.id)
            val fastSaysCurrent = fastVerdicts.contains(row.id)
            val oracleSaysCurrent = oracleVerdicts[row.id] ?: false
            if (fastSaysCurrent != oracleSaysCurrent) {
                mismatches++
                if (mismatchSamples.size < 10) {
                    mismatchSamples.add("row ${row.id} fact=${row.factId}: fast=$fastSaysCurrent oracle=$oracleSaysCurrent")
                }
            }
        }

        log.append("Fast-path current-version query: ${fastMillis}ms\n")
        log.append("Independent oracle recomputation: ${oracleMillis}ms\n\n")
        log.append("Per-row current/superseded verdict: ${allRowIds.size - mismatches} / ${allRowIds.size} matched (target: all 250,000)\n")
        if (mismatchSamples.isNotEmpty()) {
            log.append("Mismatch samples:\n")
            mismatchSamples.forEach { log.append("  $it\n") }
        }

        val fastPositions = positionProjector.currentPositionsAsOf(asOfDate)
        val oraclePositions = oracle.positionsAsOf(asOfDate)
        var positionMismatches = 0
        val positionKeys = fastPositions.keys union oraclePositions.keys
        positionKeys.forEach { key ->
            val fastPos = fastPositions[key]
            val oraclePos = oraclePositions[key]
            if (fastPos == null || oraclePos == null) {
                positionMismatches++
                return@forEach
            }
            val same = fastPos.quantity().compareTo(oraclePos.quantity) == 0 &&
                fastPos.costBasis().compareTo(oraclePos.costBasis) == 0 &&
                fastPos.realizedPnl().compareTo(oraclePos.realizedPnl) == 0
            if (!same) positionMismatches++
        }
        log.append("\nAccount/symbol position aggregates: ${positionKeys.size - positionMismatches} / ${positionKeys.size} matched\n")

        Files.createDirectories(Path.of("docs"))
        Files.writeString(Path.of("docs/bookofrecord_benchmark_output.txt"), log.toString())

        // Assertions check the counters actually measured, matching the rest of this repo's
        // benchmark style: this test fails loudly if either honest number regresses, it does
        // not pre-decide a target and tune toward it.
        assertEquals(BookOfRecordDataSeeder.TOTAL_ROWS, allRowIds.size)
        assertEquals(0, mismatches)
        assertEquals(0, positionMismatches)
    }
}
