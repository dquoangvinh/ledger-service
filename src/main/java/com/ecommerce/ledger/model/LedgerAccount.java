package com.ecommerce.ledger.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * An account in the double-entry ledger.
 *
 * <p>Ids are {@code BIGSERIAL} rather than UUID: the ledger is append-heavy and locks rows in id
 * order, both of which favour a monotonic key. {@link #ownerUserId} is the UUID of a user in
 * another service, so it is stored as a plain column with no foreign key.
 */
@Entity
@Table(name = "ledger_account")
public class LedgerAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_no", nullable = false, unique = true, length = 32)
    private String accountNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 16)
    private AccountType accountType;

    @Column(nullable = false, length = 3)
    private String currency = "VND";

    /** Owner of the account. NULL means a system account (platform cash, revenue, fees). */
    @Column(name = "owner_user_id")
    private UUID ownerUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public enum AccountType {
        ASSET,
        LIABILITY,
        EQUITY,
        REVENUE,
        EXPENSE;

        /** Assets and expenses grow on debit; everything else grows on credit. */
        public boolean increasesOnDebit() {
            return this == ASSET || this == EXPENSE;
        }
    }

    protected LedgerAccount() {
    }

    public LedgerAccount(String accountNo, AccountType accountType) {
        this(accountNo, accountType, null);
    }

    public LedgerAccount(String accountNo, AccountType accountType, UUID ownerUserId) {
        this.accountNo = accountNo;
        this.accountType = accountType;
        this.ownerUserId = ownerUserId;
    }

    public Long getId() {
        return id;
    }

    public String getAccountNo() {
        return accountNo;
    }

    public AccountType getAccountType() {
        return accountType;
    }

    public String getCurrency() {
        return currency;
    }

    public UUID getOwnerUserId() {
        return ownerUserId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
