package com.ecommerce.ledger.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One leg of a journal entry.
 *
 * <p>APPEND-ONLY: there is deliberately no setter for {@code amount} or {@code side}. Mistakes are
 * corrected by writing a reversing entry, never by editing this row - and the database enforces the
 * same rule with {@code trg_ledger_posting_immutable}.
 */
@Entity
@Table(name = "ledger_posting")
public class Posting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "entry_id")
    private JournalEntry entry;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(nullable = false, length = 1)
    private String side;

    @Column(nullable = false, precision = 20, scale = 4)
    private BigDecimal amount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Posting() {
    }

    public Posting(Long accountId, Side side, BigDecimal amount) {
        this.accountId = accountId;
        this.side = side.code();
        this.amount = amount;
    }

    public enum Side {
        DEBIT("D"),
        CREDIT("C");

        private final String code;

        Side(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }

        public Side flip() {
            return this == DEBIT ? CREDIT : DEBIT;
        }

        public static Side of(String code) {
            return "D".equals(code) ? DEBIT : CREDIT;
        }
    }

    void setEntry(JournalEntry entry) {
        this.entry = entry;
    }

    public Long getId() {
        return id;
    }

    public Long getAccountId() {
        return accountId;
    }

    public Side getSide() {
        return Side.of(side);
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
