package com.ecommerce.ledger;

import com.ecommerce.common.exception.BusinessException;
import com.ecommerce.ledger.model.LedgerAccount;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;

/**
 * Business errors raised by the ledger.
 *
 * <p>Extends {@link BusinessException} so the shared {@code GlobalExceptionHandler} maps these to
 * the project's standard error response - the ledger does not add a second exception handler.
 *
 * <p>Being a RuntimeException matters: Spring only rolls back automatically for unchecked
 * exceptions. A checked exception here would leave orphaned journal entries behind when a transfer
 * fails, which corrupts the ledger.
 *
 * <p>Every subtype carries structured data rather than only a message, so a client can tell how far
 * short a balance is without parsing English.
 */
public class LedgerException extends BusinessException {

    public LedgerException(String errorCode, String message, HttpStatus httpStatus) {
        super(message, errorCode, httpStatus);
    }

    public LedgerException(String errorCode, String message) {
        this(errorCode, message, HttpStatus.BAD_REQUEST);
    }

    /** Balance too low to cover the transfer. */
    public static class InsufficientFunds extends LedgerException {

        private final Long accountId;
        private final BigDecimal available;
        private final BigDecimal required;

        public InsufficientFunds(Long accountId, BigDecimal available, BigDecimal required) {
            super("INSUFFICIENT_FUNDS",
                    "account " + accountId + " has " + available + ", needs " + required,
                    HttpStatus.UNPROCESSABLE_ENTITY);
            this.accountId = accountId;
            this.available = available;
            this.required = required;
        }

        public Long getAccountId() {
            return accountId;
        }

        public BigDecimal getAvailable() {
            return available;
        }

        public BigDecimal getRequired() {
            return required;
        }

        /** How much is missing - clients usually want this to prompt a top-up. */
        public BigDecimal getShortfall() {
            return required.subtract(available);
        }
    }

    /** No balance row for the account. */
    public static class AccountNotFound extends LedgerException {

        private final Long accountId;

        public AccountNotFound(Long accountId) {
            super("LEDGER_ACCOUNT_NOT_FOUND", "no balance row for account " + accountId,
                    HttpStatus.NOT_FOUND);
            this.accountId = accountId;
        }

        public Long getAccountId() {
            return accountId;
        }
    }

    /**
     * Authenticated, but does not own the source account.
     *
     * <p>Deliberately carries no account id: leaking it lets an attacker probe which accounts exist
     * by telling 403 (exists, not yours) apart from 404 (does not exist).
     */
    public static class NotAccountOwner extends LedgerException {

        public NotAccountOwner(String message) {
            super("NOT_ACCOUNT_OWNER", message, HttpStatus.FORBIDDEN);
        }
    }

    /** Source and destination are the same account. */
    public static class SameAccount extends LedgerException {

        private final Long accountId;

        public SameAccount(Long accountId) {
            super("SAME_ACCOUNT", "source and destination are the same account: " + accountId,
                    HttpStatus.BAD_REQUEST);
            this.accountId = accountId;
        }

        public Long getAccountId() {
            return accountId;
        }
    }

    /**
     * An account in a transfer is not a customer wallet.
     *
     * <p>{@code transfer} debits the source and credits the destination, which only means "move
     * money out of one and into the other" for account types that grow on credit. On an ASSET a
     * debit <em>increases</em> the balance, so the funds check would refuse a transfer that was
     * about to add money, and the destination would be driven negative with nothing to stop it.
     * Rather than guess a direction, the operation refuses the account.
     */
    public static class NotAWalletAccount extends LedgerException {

        private final Long accountId;

        public NotAWalletAccount(Long accountId, LedgerAccount.AccountType actual) {
            super("NOT_A_WALLET_ACCOUNT",
                    "account " + accountId + " is " + actual
                            + "; transfer moves money between LIABILITY accounts only",
                    HttpStatus.UNPROCESSABLE_ENTITY);
            this.accountId = accountId;
        }

        public Long getAccountId() {
            return accountId;
        }
    }

    /** Reversing a reversal, or reversing the same entry twice. */
    public static class InvalidReversal extends LedgerException {

        private final Long entryId;

        public InvalidReversal(Long entryId, String message) {
            super("INVALID_REVERSAL", message, HttpStatus.CONFLICT);
            this.entryId = entryId;
        }

        public Long getEntryId() {
            return entryId;
        }
    }

    /** The two accounts hold different currencies. */
    public static class CurrencyMismatch extends LedgerException {

        private final String fromCurrency;
        private final String toCurrency;

        public CurrencyMismatch(String fromCurrency, String toCurrency) {
            super("CURRENCY_MISMATCH", "cannot transfer between " + fromCurrency + " and " + toCurrency,
                    HttpStatus.UNPROCESSABLE_ENTITY);
            this.fromCurrency = fromCurrency;
            this.toCurrency = toCurrency;
        }

        public String getFromCurrency() {
            return fromCurrency;
        }

        public String getToCurrency() {
            return toCurrency;
        }
    }

    /** Amount is missing or not positive. */
    public static class InvalidAmount extends LedgerException {

        public InvalidAmount(String message) {
            super("INVALID_AMOUNT", message, HttpStatus.BAD_REQUEST);
        }
    }

    /** Two concurrent requests carried the same idempotency key. */
    public static class DuplicateRequest extends LedgerException {

        private final String idempotencyKey;

        public DuplicateRequest(String idempotencyKey) {
            super("DUPLICATE_REQUEST", "concurrent request with the same idempotency key",
                    HttpStatus.CONFLICT);
            this.idempotencyKey = idempotencyKey;
        }

        public String getIdempotencyKey() {
            return idempotencyKey;
        }
    }

    /** The referenced journal entry does not exist. */
    public static class EntryNotFound extends LedgerException {

        public EntryNotFound(Long entryId) {
            super("LEDGER_ENTRY_NOT_FOUND", "entry not found: " + entryId, HttpStatus.NOT_FOUND);
        }
    }
}
