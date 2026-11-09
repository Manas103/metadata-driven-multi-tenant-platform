package com.manas.metaplatform.bookofrecord.integration

import com.manas.metaplatform.bookofrecord.generator.BookOfRecordDataSeeder
import com.manas.metaplatform.bookofrecord.oracle.IndependentRecomputation
import com.manas.metaplatform.bookofrecord.projection.PositionProjector
import com.manas.metaplatform.bookofrecord.repository.LedgerEntryRepository
import com.manas.metaplatform.domain.Tenant
import com.manas.metaplatform.repository.TenantRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import kotlin.test.assertEquals

/**
 * Fast, always-on wiring check: a small seeded slice (not the 500-account / 250,000-row
 * scale the resume claims, which is [com.manas.metaplatform.bookofrecord.bench.RestatementBenchmarkRunner]'s
 * job), confirming the fast path ([PositionProjector], SQL GROUP BY over `as_of_to is null`)
 * and the independent oracle ([IndependentRecomputation], raw JDBC, decides "current" from
 * `as_of_from` alone) agree on both the per-row current/superseded verdict and the summed
 * positions.
 */
@SpringBootTest
@Transactional
class BookOfRecordIntegrationTest {

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
    fun fastPathAndOracleAgreeOnASmallSeededSlice() {
        val smallAccountCount = 5
        val tenant = tenantRepository.save(Tenant("WiringCheckFund"))
        val result = seeder.seed(tenant.id, 42L, smallAccountCount)
        assertEquals(smallAccountCount * BookOfRecordDataSeeder.ROWS_PER_ACCOUNT, result.insertedRows)
        assertEquals(ledgerEntryRepository.countAll(), result.insertedRows.toLong())

        val asOfDate = LocalDate.of(2026, 11, 1)
        val fastVerdicts = ledgerEntryRepository.findCurrentAsOf(asOfDate).map { it.id }.toSet()
        val oracleVerdicts = oracle.computeCurrentVerdicts()
        val allRows = ledgerEntryRepository.findAll()

        var mismatches = 0
        allRows.forEach { row ->
            val fastSaysCurrent = fastVerdicts.contains(row.id) && row.effectiveDate <= asOfDate
            val oracleSaysCurrent = (oracleVerdicts[row.id] ?: false) && row.effectiveDate <= asOfDate
            if (fastSaysCurrent != oracleSaysCurrent) mismatches++
        }
        assertEquals(0, mismatches)

        val fastPositions = positionProjector.currentPositionsAsOf(asOfDate)
        val oraclePositions = oracle.positionsAsOf(asOfDate)
        assertEquals(oraclePositions.keys, fastPositions.keys)
        fastPositions.forEach { (key, fastPos) ->
            val oraclePos = oraclePositions[key]!!
            assertEquals(0, fastPos.quantity().compareTo(oraclePos.quantity))
            assertEquals(0, fastPos.costBasis().compareTo(oraclePos.costBasis))
            assertEquals(0, fastPos.realizedPnl().compareTo(oraclePos.realizedPnl))
        }
    }
}
