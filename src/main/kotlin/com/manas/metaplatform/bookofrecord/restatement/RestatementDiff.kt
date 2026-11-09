package com.manas.metaplatform.bookofrecord.restatement

import com.manas.metaplatform.bookofrecord.domain.LedgerEntry
import java.math.BigDecimal

/** One named field's prior and restated value, for the React console to render by name. */
data class FieldDiff(
    val fieldName: String,
    val priorValue: String,
    val restatedValue: String,
    val changed: Boolean,
)

data class RestatementDiff(
    val factId: String,
    val accountId: Long,
    val symbol: String,
    val effectiveDate: String,
    val priorAsOfFrom: String,
    val restatedAsOfFrom: String,
    val fields: List<FieldDiff>,
) {
    companion object {
        /** Builds the diff between the superseded version and the version that replaced it. */
        fun of(prior: LedgerEntry, restated: LedgerEntry): RestatementDiff {
            require(prior.factId == restated.factId) { "versions must share a fact id" }
            val fields = listOf(
                fieldDiff("quantity", prior.quantity, restated.quantity),
                fieldDiff("costBasis", prior.costBasis, restated.costBasis),
                fieldDiff("realizedPnl", prior.realizedPnl, restated.realizedPnl),
                fieldDiff("effectiveDate", prior.effectiveDate.toString(), restated.effectiveDate.toString()),
                fieldDiff("entryType", prior.entryType.toString(), restated.entryType.toString()),
            )
            return RestatementDiff(
                factId = prior.factId,
                accountId = prior.accountId,
                symbol = prior.symbol,
                effectiveDate = prior.effectiveDate.toString(),
                priorAsOfFrom = prior.asOfFrom.toString(),
                restatedAsOfFrom = restated.asOfFrom.toString(),
                fields = fields,
            )
        }

        private fun fieldDiff(name: String, prior: BigDecimal, restated: BigDecimal): FieldDiff =
            FieldDiff(name, prior.toPlainString(), restated.toPlainString(), prior.compareTo(restated) != 0)

        private fun fieldDiff(name: String, prior: String, restated: String): FieldDiff =
            FieldDiff(name, prior, restated, prior != restated)
    }
}
