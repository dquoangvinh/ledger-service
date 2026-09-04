package com.ecommerce.ledger.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Cached balance for one account.
 *
 * <p>This is a cache: the authoritative number is always the sum of the postings, which
 * {@code LedgerService.derivedBalance} recomputes for reconciliation.
 */
@Entity
@Table(name = "ledger_account_balance")
public class LedgerAccountBalance {

    @Id
    @Column(name = "account_id")
    private Long accountId;

    @Column(nullable = false, precision = 20, scale = 4)
    private BigDecimal balance = BigDecimal.ZERO;

    /** Only read in optimistic mode; the pessimistic path locks the row instead. */
    @Version
    private Long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected LedgerAccountBalance() {
    }

    public LedgerAccountBalance(Long accountId, BigDecimal opening) {
        this.accountId = accountId;
        this.balance = opening;
    }

    public Long getAccountId() {
        return accountId;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public Long getVersion() {
        return version;
    }

    public void add(BigDecimal delta) {
        this.balance = this.balance.add(delta);
        this.updatedAt = Instant.now();
    }
}
