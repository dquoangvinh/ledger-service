package com.ecommerce.ledger;

import com.ecommerce.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Why every ledger exception has to be unchecked.
 *
 * <p>Spring's declarative transactions roll back on {@link RuntimeException} and {@link Error},
 * and commit on a checked exception unless {@code rollbackFor} says otherwise. That default is
 * easy to read past, and on a ledger it is the difference between refusing a transfer and leaving
 * half of one on the books.
 *
 * <p>{@code LedgerException extends BusinessException extends RuntimeException}, so today the
 * ledger is on the right side of it. This test pins that: it writes a row and then throws, once
 * with each kind of exception, and counts what survived. If someone later makes a ledger
 * exception checked, the second half of this test starts failing.
 */
@Import(LedgerRollbackSemanticsIntegrationTest.RollbackProbeConfig.class)
@DisplayName("Rollback depends on the exception type, not on intent")
class LedgerRollbackSemanticsIntegrationTest extends AbstractLedgerIntegrationTest {

    /** A checked exception, so the probe below can throw one deliberately. */
    static class CheckedFailure extends Exception {
        CheckedFailure(String message) {
            super(message);
        }
    }

    /**
     * Has to be a separate bean: a self-invocation inside LedgerService would bypass the
     * transactional proxy and prove nothing about rollback at all.
     */
    @Component
    static class RollbackProbe {

        private final LedgerService ledger;

        RollbackProbe(LedgerService ledger) {
            this.ledger = ledger;
        }

        /** openAccount is itself @Transactional, so it joins this transaction rather than
         *  committing on its own - the throw below decides the fate of both. */
        @Transactional
        void openAccountThenThrowUnchecked(String accountNo) {
            ledger.openAccount(accountNo, com.ecommerce.ledger.model.LedgerAccount.AccountType.LIABILITY,
                    new BigDecimal("1000"));
            throw new BusinessException("refused after writing");
        }

        @Transactional
        void openAccountThenThrowChecked(String accountNo) throws CheckedFailure {
            ledger.openAccount(accountNo, com.ecommerce.ledger.model.LedgerAccount.AccountType.LIABILITY,
                    new BigDecimal("1000"));
            throw new CheckedFailure("refused after writing");
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RollbackProbeConfig {
        @Bean
        RollbackProbe rollbackProbe(LedgerService ledger) {
            return new RollbackProbe(ledger);
        }
    }

    @Autowired
    private RollbackProbe probe;

    @BeforeEach
    void clean() {
        truncateLedger();
    }

    private int accountsNamed(String accountNo) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM ledger_account WHERE account_no = ?", Integer.class, accountNo);
        return n == null ? 0 : n;
    }

    @Test
    @DisplayName("an unchecked failure takes the row with it")
    void uncheckedExceptionRollsBackTheWrite() {
        String accountNo = newAccountNo("RB-UNCHECKED");

        assertThatThrownBy(() -> probe.openAccountThenThrowUnchecked(accountNo))
                .isInstanceOf(RuntimeException.class);

        assertThat(accountsNamed(accountNo))
                .as("the account written before the throw must not survive")
                .isZero();
    }

    @Test
    @DisplayName("a checked failure leaves the row behind - the ledger would keep it")
    void checkedExceptionCommitsTheWriteAnyway() {
        String accountNo = newAccountNo("RB-CHECKED");

        assertThatThrownBy(() -> probe.openAccountThenThrowChecked(accountNo))
                .isInstanceOf(CheckedFailure.class);

        // Not a bug being reported - this is Spring behaving as documented, and it is exactly why
        // LedgerException must stay unchecked.
        assertThat(accountsNamed(accountNo))
                .as("Spring commits on a checked exception, so the orphan row survives")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("every ledger exception is unchecked, so the first case is the one that applies")
    void ledgerExceptionsAreUnchecked() {
        assertThat(RuntimeException.class)
                .as("LedgerException must stay on the rolling-back side of Spring's default")
                .isAssignableFrom(LedgerException.class);
    }
}
