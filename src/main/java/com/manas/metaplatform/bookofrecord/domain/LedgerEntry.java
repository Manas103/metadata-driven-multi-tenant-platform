package com.manas.metaplatform.bookofrecord.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One bitemporal version of one economic fact (a trade or a corporate-action adjustment)
 * against one account/symbol. {@code effectiveDate} is business (valid) time: the date the
 * fact actually happened in the market. {@code asOfFrom}/{@code asOfTo} is system
 * (transaction) time: the window during which this version was the record's current
 * belief. {@code factId} is stable across every version of "the same fact"; a correction
 * never updates {@code quantity}, {@code costBasis}, {@code realizedPnl} or
 * {@code effectiveDate} on an existing row, it only ever closes that row's
 * {@code asOfTo} and inserts a new row with the same {@code factId} and a later
 * {@code asOfFrom}. {@link com.manas.metaplatform.bookofrecord.restatement.RestatementWriter}
 * is the only code in this package that is allowed to touch {@code asOfTo}.
 */
@Entity
@Table(name = "ledger_entries")
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "fact_id", nullable = false, length = 64)
    private String factId;

    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "symbol", nullable = false, length = 16)
    private String symbol;

    @Column(name = "effective_date", nullable = false)
    private LocalDate effectiveDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 20)
    private LedgerEntryType entryType;

    @Column(name = "quantity", nullable = false, precision = 20, scale = 6)
    private BigDecimal quantity;

    @Column(name = "cost_basis", nullable = false, precision = 20, scale = 6)
    private BigDecimal costBasis;

    @Column(name = "realized_pnl", nullable = false, precision = 20, scale = 6)
    private BigDecimal realizedPnl;

    @Column(name = "as_of_from", nullable = false)
    private Instant asOfFrom;

    @Column(name = "as_of_to")
    private Instant asOfTo;

    protected LedgerEntry() {
    }

    public LedgerEntry(Long accountId, String factId, int version, String symbol, LocalDate effectiveDate,
                        LedgerEntryType entryType, BigDecimal quantity, BigDecimal costBasis,
                        BigDecimal realizedPnl, Instant asOfFrom) {
        this.accountId = accountId;
        this.factId = factId;
        this.version = version;
        this.symbol = symbol;
        this.effectiveDate = effectiveDate;
        this.entryType = entryType;
        this.quantity = quantity;
        this.costBasis = costBasis;
        this.realizedPnl = realizedPnl;
        this.asOfFrom = asOfFrom;
        this.asOfTo = null;
    }

    public Long getId() {
        return id;
    }

    public Long getAccountId() {
        return accountId;
    }

    public String getFactId() {
        return factId;
    }

    public int getVersion() {
        return version;
    }

    public String getSymbol() {
        return symbol;
    }

    public LocalDate getEffectiveDate() {
        return effectiveDate;
    }

    public LedgerEntryType getEntryType() {
        return entryType;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public BigDecimal getCostBasis() {
        return costBasis;
    }

    public BigDecimal getRealizedPnl() {
        return realizedPnl;
    }

    public Instant getAsOfFrom() {
        return asOfFrom;
    }

    public Instant getAsOfTo() {
        return asOfTo;
    }

    /** Only {@link com.manas.metaplatform.bookofrecord.restatement.RestatementWriter} calls this. */
    public void closeAsOf(Instant closedAt) {
        this.asOfTo = closedAt;
    }
}
