package com.manas.metaplatform.bookofrecord.generator;

import com.manas.metaplatform.bookofrecord.domain.FundAccount;
import com.manas.metaplatform.bookofrecord.repository.FundAccountRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Builds the 500-account, 250,000-ledger-row synthetic dataset directly through JDBC
 * batch inserts rather than 250,000 individual JPA saves, which with an {@code IDENTITY}
 * primary key would force one database round trip per row. This is a backfill-shaped
 * bulk load, not the live correction path: {@link com.manas.metaplatform.bookofrecord.restatement.RestatementWriter}
 * is the code exercised by {@code RestatementWriterTest} for the live single-correction
 * behavior (close then insert); this seeder constructs the equivalent end state for 500
 * accounts' worth of history in one shot, with explicit primary keys, and is used only by
 * the benchmark and the integration test, never by production code.
 *
 * <p>Per account: 450 facts across 10 symbols (45 each), 50 of which receive exactly one
 * correction (a second version). 450 + 50 = 500 rows/account; 500 accounts x 500 =
 * 250,000 rows exactly.</p>
 */
@Component
public final class BookOfRecordDataSeeder {

    public static final int ACCOUNT_COUNT = 500;
    public static final int SYMBOLS_PER_ACCOUNT = 10;
    public static final int FACTS_PER_SYMBOL = 45;
    public static final int FACTS_PER_ACCOUNT = SYMBOLS_PER_ACCOUNT * FACTS_PER_SYMBOL; // 450
    public static final int CORRECTIONS_PER_ACCOUNT = 50;
    public static final int ROWS_PER_ACCOUNT = FACTS_PER_ACCOUNT + CORRECTIONS_PER_ACCOUNT; // 500
    public static final int TOTAL_ROWS = ACCOUNT_COUNT * ROWS_PER_ACCOUNT; // 250,000

    private static final String[] SYMBOLS =
            {"AAPL", "MSFT", "GOOG", "AMZN", "NVDA", "META", "BRK", "JPM", "XOM", "UNH"};

    private final FundAccountRepository fundAccountRepository;
    private final JdbcTemplate jdbcTemplate;

    public BookOfRecordDataSeeder(FundAccountRepository fundAccountRepository, JdbcTemplate jdbcTemplate) {
        this.fundAccountRepository = fundAccountRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    public SeedResult seed(Long tenantId, long randomSeed) {
        return seed(tenantId, randomSeed, ACCOUNT_COUNT);
    }

    /** Overload used by the fast wiring-check test to seed a small slice instead of the full 500 accounts. */
    public SeedResult seed(Long tenantId, long randomSeed, int accountCount) {
        Random random = new Random(randomSeed);
        List<Long> accountIds = new ArrayList<>(accountCount);
        for (int a = 0; a < accountCount; a++) {
            FundAccount account = fundAccountRepository.save(new FundAccount(tenantId, "Fund-" + a));
            accountIds.add(account.getId());
        }

        Instant now = Instant.parse("2026-11-01T00:00:00Z");
        List<Object[]> rows = new ArrayList<>(accountCount * ROWS_PER_ACCOUNT);
        long nextId = 1;
        long correctedRowCount = 0;

        for (Long accountId : accountIds) {
            List<Integer> factIndexes = new ArrayList<>(FACTS_PER_ACCOUNT);
            for (int i = 0; i < FACTS_PER_ACCOUNT; i++) {
                factIndexes.add(i);
            }
            java.util.Collections.shuffle(factIndexes, random);
            java.util.Set<Integer> correctedFactIndexes =
                    new java.util.HashSet<>(factIndexes.subList(0, CORRECTIONS_PER_ACCOUNT));

            for (int factIndex = 0; factIndex < FACTS_PER_ACCOUNT; factIndex++) {
                String symbol = SYMBOLS[factIndex / FACTS_PER_SYMBOL];
                String factId = accountId + "-" + factIndex;

                LocalDate effectiveDate = randomPastDate(random);
                Instant bookedAt = effectiveDate.atStartOfDay(java.time.ZoneOffset.UTC).toInstant()
                        .plus(random.nextInt(3), ChronoUnit.DAYS);
                boolean isCorporateAction = random.nextInt(5) == 0;
                String entryType = isCorporateAction ? "CORPORATE_ACTION" : "TRADE";

                BigDecimal quantity = randomSignedDecimal(random, 1, 500);
                BigDecimal costBasis = randomSignedDecimal(random, 10, 50000);
                BigDecimal realizedPnl = randomSignedDecimal(random, -5000, 5000);

                boolean hasCorrection = correctedFactIndexes.contains(factIndex);
                if (hasCorrection) {
                    Instant correctionAt = bookedAt.plus(10 + random.nextInt(50), ChronoUnit.DAYS);
                    if (correctionAt.isAfter(now)) {
                        correctionAt = now;
                    }
                    rows.add(ledgerRow(nextId++, accountId, factId, 1, symbol, effectiveDate, entryType,
                            quantity, costBasis, realizedPnl, bookedAt, correctionAt));

                    BigDecimal correctedQuantity = quantity.add(randomSignedDecimal(random, 1, 20));
                    BigDecimal correctedCostBasis = costBasis.add(randomSignedDecimal(random, 10, 500));
                    BigDecimal correctedPnl = realizedPnl.add(randomSignedDecimal(random, -200, 200));
                    rows.add(ledgerRow(nextId++, accountId, factId, 2, symbol, effectiveDate, entryType,
                            correctedQuantity, correctedCostBasis, correctedPnl, correctionAt, null));
                    correctedRowCount++;
                } else {
                    rows.add(ledgerRow(nextId++, accountId, factId, 1, symbol, effectiveDate, entryType,
                            quantity, costBasis, realizedPnl, bookedAt, null));
                }
            }
        }

        int insertedRows = batchInsert(rows);

        return new SeedResult(accountIds, rows.size(), correctedRowCount, insertedRows);
    }

    private Object[] ledgerRow(long id, Long accountId, String factId, int version, String symbol,
                                LocalDate effectiveDate, String entryType, BigDecimal quantity,
                                BigDecimal costBasis, BigDecimal realizedPnl, Instant asOfFrom, Instant asOfTo) {
        return new Object[]{id, accountId, factId, version, symbol, java.sql.Date.valueOf(effectiveDate),
                entryType, quantity, costBasis, realizedPnl, Timestamp.from(asOfFrom),
                asOfTo == null ? null : Timestamp.from(asOfTo)};
    }

    private int batchInsert(List<Object[]> rows) {
        String sql = """
                insert into ledger_entries
                    (id, account_id, fact_id, version, symbol, effective_date, entry_type,
                     quantity, cost_basis, realized_pnl, as_of_from, as_of_to)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        int[] argTypes = {Types.BIGINT, Types.BIGINT, Types.VARCHAR, Types.INTEGER, Types.VARCHAR, Types.DATE,
                Types.VARCHAR, Types.DECIMAL, Types.DECIMAL, Types.DECIMAL, Types.TIMESTAMP, Types.TIMESTAMP};

        int batchSize = 1000;
        int total = 0;
        for (int start = 0; start < rows.size(); start += batchSize) {
            List<Object[]> chunk = rows.subList(start, Math.min(start + batchSize, rows.size()));
            total += jdbcTemplate.batchUpdate(sql, chunk, argTypes).length;
        }
        return total;
    }

    private static LocalDate randomPastDate(Random random) {
        LocalDate start = LocalDate.of(2024, 1, 1);
        long daysRange = ChronoUnit.DAYS.between(start, LocalDate.of(2026, 10, 1));
        return start.plusDays(random.nextInt((int) daysRange));
    }

    private static BigDecimal randomSignedDecimal(Random random, int min, int max) {
        int magnitude = min + random.nextInt(Math.max(1, max - min));
        boolean negative = min < 0 && random.nextBoolean();
        BigDecimal value = BigDecimal.valueOf(magnitude).setScale(2);
        return negative ? value.negate() : value;
    }

    public record SeedResult(List<Long> accountIds, long totalRows, long correctedFactCount, int insertedRows) {
    }
}
