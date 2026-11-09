package com.manas.metaplatform.bookofrecord.repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

import com.manas.metaplatform.bookofrecord.domain.LedgerEntry;

/**
 * The fast path's position aggregation: a single SQL-side GROUP BY over the rows the
 * database already knows are current ({@code as_of_to is null}), rather than pulling
 * every version of every fact into the JVM and figuring out "current" there. This is
 * the thing {@code IndependentRecomputation} (Kotlin) is a deliberately different
 * implementation of.
 */
public interface PositionAggregateRepository extends Repository<LedgerEntry, Long> {

    @Query("""
            select e.accountId as accountId, e.symbol as symbol,
                   sum(e.quantity) as quantity, sum(e.costBasis) as costBasis, sum(e.realizedPnl) as realizedPnl
            from LedgerEntry e
            where e.asOfTo is null and e.effectiveDate <= :asOfDate
            group by e.accountId, e.symbol
            """)
    List<PositionRow> aggregateCurrentPositions(@Param("asOfDate") LocalDate asOfDate);

    interface PositionRow {
        Long getAccountId();

        String getSymbol();

        java.math.BigDecimal getQuantity();

        java.math.BigDecimal getCostBasis();

        java.math.BigDecimal getRealizedPnl();
    }
}
