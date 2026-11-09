package com.manas.metaplatform.bookofrecord.web

import com.manas.metaplatform.bookofrecord.projection.PositionProjector
import com.manas.metaplatform.bookofrecord.repository.LedgerEntryRepository
import com.manas.metaplatform.bookofrecord.restatement.RestatementDiff
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.springframework.http.HttpStatus
import java.time.LocalDate

/** Backs the React restatement console: one endpoint per fact's before/after field diff. */
@RestController
@RequestMapping("/api/bookofrecord")
class RestatementController(
    private val ledgerEntryRepository: LedgerEntryRepository,
    private val positionProjector: PositionProjector,
) {

    @GetMapping("/accounts/{accountId}/positions")
    fun positions(@PathVariable accountId: Long, @RequestParam asOf: String): List<PositionProjector.Position> =
        positionProjector.currentPositionsAsOf(LocalDate.parse(asOf))
            .values
            .filter { it.accountId() == accountId }

    @GetMapping("/restatements/{factId}")
    fun restatement(@PathVariable factId: String): RestatementDiff {
        val versions = ledgerEntryRepository.findByFactIdOrderByVersionAsc(factId)
        if (versions.size < 2) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "fact $factId was never restated")
        }
        val prior = versions[versions.size - 2]
        val restated = versions[versions.size - 1]
        return RestatementDiff.of(prior, restated)
    }
}
