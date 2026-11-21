package com.manas.metaplatform.bookofrecord.web

import com.manas.metaplatform.bookofrecord.domain.LedgerEntry
import com.manas.metaplatform.bookofrecord.projection.PositionProjector
import com.manas.metaplatform.bookofrecord.repository.FundAccountRepository
import com.manas.metaplatform.bookofrecord.repository.LedgerEntryRepository
import com.manas.metaplatform.bookofrecord.restatement.RestatementDiff
import com.manas.metaplatform.bookofrecord.restatement.RestatementWriter
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/** Backs the console's ledger page: accounts, restatements, version history, live corrections. */
@RestController
@RequestMapping("/api/bookofrecord")
class RestatementController(
    private val ledgerEntryRepository: LedgerEntryRepository,
    private val fundAccountRepository: FundAccountRepository,
    private val positionProjector: PositionProjector,
    private val restatementWriter: RestatementWriter,
) {

    data class AccountView(val id: Long, val name: String)

    data class VersionView(
        val version: Int,
        val symbol: String,
        val effectiveDate: LocalDate,
        val entryType: String,
        val quantity: BigDecimal,
        val costBasis: BigDecimal,
        val realizedPnl: BigDecimal,
        val asOfFrom: Instant,
        val asOfTo: Instant?,
    )

    data class Correction(val quantity: BigDecimal?, val costBasis: BigDecimal?, val realizedPnl: BigDecimal?)

    @GetMapping("/accounts")
    fun accounts(@RequestParam(defaultValue = "50") limit: Int): List<AccountView> =
        fundAccountRepository.findAll(PageRequest.of(0, limit.coerceIn(1, 500), Sort.by("id")))
            .content.map { AccountView(it.id, it.accountName) }

    @GetMapping("/accounts/{accountId}/positions")
    fun positions(@PathVariable accountId: Long, @RequestParam asOf: String): List<PositionProjector.Position> =
        positionProjector.currentPositionsAsOf(LocalDate.parse(asOf))
            .values
            .filter { it.accountId() == accountId }

    @GetMapping("/accounts/{accountId}/restated-facts")
    fun restatedFacts(@PathVariable accountId: Long): List<String> =
        ledgerEntryRepository.restatedFactIds(accountId)

    /** What the system believed at [believedAt] (default now) about entries effective by [asOf]. */
    @GetMapping("/accounts/{accountId}/entries")
    fun entries(
        @PathVariable accountId: Long,
        @RequestParam asOf: String,
        @RequestParam(required = false) believedAt: String?,
    ): List<VersionView> =
        ledgerEntryRepository.believedAt(
            accountId, LocalDate.parse(asOf), believedAt?.let(Instant::parse) ?: Instant.now(),
        ).map(::view)

    @GetMapping("/facts/{factId}/versions")
    fun versions(@PathVariable factId: String): List<VersionView> {
        val versions = ledgerEntryRepository.findByFactIdOrderByVersionAsc(factId)
        if (versions.isEmpty()) throw ResponseStatusException(HttpStatus.NOT_FOUND, "no fact $factId")
        return versions.map(::view)
    }

    @GetMapping("/restatements/{factId}")
    fun restatement(@PathVariable factId: String): RestatementDiff {
        val versions = ledgerEntryRepository.findByFactIdOrderByVersionAsc(factId)
        if (versions.size < 2) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "fact $factId was never restated")
        }
        return RestatementDiff.of(versions[versions.size - 2], versions[versions.size - 1])
    }

    /** Books a correction now: closes the open version and opens the next. Omitted fields keep their value. */
    @PostMapping("/restatements/{factId}")
    fun correct(@PathVariable factId: String, @RequestBody body: Correction): RestatementDiff {
        val versions = ledgerEntryRepository.findByFactIdOrderByVersionAsc(factId)
        val open = versions.firstOrNull { it.asOfTo == null }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "no open version of fact $factId")
        val restated = restatementWriter.correct(
            factId,
            body.quantity ?: open.quantity,
            body.costBasis ?: open.costBasis,
            body.realizedPnl ?: open.realizedPnl,
            Instant.now(),
        )
        return RestatementDiff.of(open, restated)
    }

    private fun view(e: LedgerEntry) = VersionView(
        e.version, e.symbol, e.effectiveDate, e.entryType.name, e.quantity, e.costBasis, e.realizedPnl,
        e.asOfFrom, e.asOfTo,
    )
}
