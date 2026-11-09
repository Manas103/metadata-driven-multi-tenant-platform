package com.manas.metaplatform.bookofrecord.oracle

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate

/**
 * A second, independent implementation of "what is the current version of this fact",
 * used only as a diff oracle against [com.manas.metaplatform.bookofrecord.projection.PositionProjector].
 * It shares no code with that path: it queries raw rows through its own [JdbcTemplate]
 * call rather than Hibernate/JPA, and it decides which version of a fact is current by
 * comparing [RawLedgerRow.asOfFrom] across every version sharing a [RawLedgerRow.factId]
 * (the latest wins), never by reading the `as_of_to` column the fast path trusts. A
 * mismatch between the two means the writer failed to maintain `as_of_to` correctly, not
 * that the oracle disagrees with itself.
 */
@Component
class IndependentRecomputation(private val jdbcTemplate: JdbcTemplate) {

    data class RawLedgerRow(
        val id: Long,
        val accountId: Long,
        val factId: String,
        val symbol: String,
        val effectiveDate: LocalDate,
        val quantity: BigDecimal,
        val costBasis: BigDecimal,
        val realizedPnl: BigDecimal,
        val asOfFrom: Instant,
    )

    data class Position(
        val accountId: Long,
        val symbol: String,
        val quantity: BigDecimal,
        val costBasis: BigDecimal,
        val realizedPnl: BigDecimal,
    )

    private fun loadAllRows(): List<RawLedgerRow> =
        jdbcTemplate.query(
            "select id, account_id, fact_id, symbol, effective_date, quantity, cost_basis, realized_pnl, as_of_from from ledger_entries"
        ) { rs: ResultSet, _: Int ->
            RawLedgerRow(
                id = rs.getLong("id"),
                accountId = rs.getLong("account_id"),
                factId = rs.getString("fact_id"),
                symbol = rs.getString("symbol"),
                effectiveDate = rs.getDate("effective_date").toLocalDate(),
                quantity = rs.getBigDecimal("quantity"),
                costBasis = rs.getBigDecimal("cost_basis"),
                realizedPnl = rs.getBigDecimal("realized_pnl"),
                asOfFrom = rs.getTimestamp("as_of_from").toInstant(),
            )
        }

    /** Per ledger-entry-id verdict: is this row the current version of its fact, by this independent method. */
    fun computeCurrentVerdicts(): Map<Long, Boolean> {
        val rows = loadAllRows()
        val verdicts = HashMap<Long, Boolean>(rows.size)
        rows.groupBy { it.factId }.values.forEach { versions ->
            val currentId = versions.maxBy { it.asOfFrom }.id
            versions.forEach { verdicts[it.id] = (it.id == currentId) }
        }
        return verdicts
    }

    /** Key: "accountId:symbol". Sums only the current version of each fact, independently determined. */
    fun positionsAsOf(asOfDate: LocalDate): Map<String, Position> {
        val rows = loadAllRows()
        val currentVersions = rows.groupBy { it.factId }.values
            .map { versions -> versions.maxBy { it.asOfFrom } }
            .filter { it.effectiveDate <= asOfDate }

        return currentVersions.groupBy { "${it.accountId}:${it.symbol}" }
            .mapValues { (_, entries) ->
                Position(
                    accountId = entries.first().accountId,
                    symbol = entries.first().symbol,
                    quantity = entries.fold(BigDecimal.ZERO) { acc, e -> acc.add(e.quantity) },
                    costBasis = entries.fold(BigDecimal.ZERO) { acc, e -> acc.add(e.costBasis) },
                    realizedPnl = entries.fold(BigDecimal.ZERO) { acc, e -> acc.add(e.realizedPnl) },
                )
            }
    }
}
