package com.manas.metaplatform.bookofrecord.projection;

import com.manas.metaplatform.bookofrecord.repository.PositionAggregateRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The fast, production-shaped read path: one SQL GROUP BY over the rows the database
 * already believes are current. This is the path a real query endpoint would take.
 * It trusts {@code as_of_to} to have been maintained correctly by
 * {@link com.manas.metaplatform.bookofrecord.restatement.RestatementWriter}, which is
 * exactly the assumption {@code IndependentRecomputation} checks from the opposite
 * direction.
 */
@Component
public final class PositionProjector {

    private final PositionAggregateRepository aggregateRepository;

    public PositionProjector(PositionAggregateRepository aggregateRepository) {
        this.aggregateRepository = aggregateRepository;
    }

    /** Key: "accountId:symbol". */
    public Map<String, Position> currentPositionsAsOf(LocalDate asOfDate) {
        Map<String, Position> positions = new LinkedHashMap<>();
        for (PositionAggregateRepository.PositionRow row : aggregateRepository.aggregateCurrentPositions(asOfDate)) {
            String key = row.getAccountId() + ":" + row.getSymbol();
            positions.put(key, new Position(row.getAccountId(), row.getSymbol(),
                    row.getQuantity(), row.getCostBasis(), row.getRealizedPnl()));
        }
        return positions;
    }

    public record Position(Long accountId, String symbol, BigDecimal quantity, BigDecimal costBasis,
                            BigDecimal realizedPnl) {
    }
}
