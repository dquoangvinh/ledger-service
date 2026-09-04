package com.ecommerce.ledger;

import com.ecommerce.ledger.repository.JournalEntryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The retry path, driven by mocks rather than by a real deadlock.
 *
 * <p>Provoking a genuine deadlock through the service is not possible by design - {@code lockOrder}
 * exists precisely to stop two transfers taking the accounts in opposite order - and staging one
 * around it would make the test a race, which is the wrong shape for asserting a retry count. What
 * matters here is the decision logic: how many attempts, which exceptions qualify, and whether the
 * key survives. PostgreSQL's own behaviour is not in question; it was confirmed separately by
 * forcing "ERROR: deadlock detected" out of two opposed sessions.
 */
@ExtendWith(MockitoExtension.class)
class TransferFacadeRetryTest {

    private static final UUID ACTING_USER = UUID.randomUUID();
    private static final String KEY = "retry-key-0001";
    private static final BigDecimal AMOUNT = new BigDecimal("10.0000");

    @Mock
    private LedgerService ledger;

    @Mock
    private JournalEntryRepository entryRepository;

    @InjectMocks
    private TransferFacade facade;

    @Test
    @DisplayName("A lost deadlock is retried, and the caller sees the retry's result")
    void aLostDeadlockIsRetried() {
        when(ledger.transferAs(any(), any(), any(), any(), any()))
                .thenThrow(new CannotAcquireLockException("deadlock detected"))
                .thenReturn(42L);

        assertThat(facade.transferAs(ACTING_USER, 1L, 2L, AMOUNT, KEY)).isEqualTo(42L);

        // The same key both times. A fresh one would turn a single request into a second transfer -
        // and would also lose the protection of landing on the duplicate branch if the first
        // attempt had in fact committed.
        verify(ledger, times(2)).transferAs(any(), any(), any(), any(), eq(KEY));
    }

    @Test
    @DisplayName("Deadlocking on every attempt gives up and surfaces the original exception")
    void deadlockOnEveryAttemptGivesUp() {
        when(ledger.transferAs(any(), any(), any(), any(), any()))
                .thenThrow(new CannotAcquireLockException("deadlock detected"));

        // The original exception rather than a wrapper: it carries the SQLSTATE and the server-side
        // detail that whoever reads the log will need.
        assertThatThrownBy(() -> facade.transferAs(ACTING_USER, 1L, 2L, AMOUNT, KEY))
                .isInstanceOf(CannotAcquireLockException.class)
                .hasMessageContaining("deadlock detected");

        verify(ledger, times(3)).transferAs(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("A business refusal is not retried")
    void aBusinessRefusalIsNotRetried() {
        when(ledger.transferAs(any(), any(), any(), any(), any()))
                .thenThrow(new LedgerException.InsufficientFunds(1L, BigDecimal.ZERO, AMOUNT));

        assertThatThrownBy(() -> facade.transferAs(ACTING_USER, 1L, 2L, AMOUNT, KEY))
                .isInstanceOf(LedgerException.InsufficientFunds.class);

        // Retrying an account that is short of money just fails again more slowly.
        verify(ledger, times(1)).transferAs(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("reverse() gets the same retry, not just transfer()")
    void reverseIsRetriedToo() {
        when(ledger.reverse(any(), any()))
                .thenThrow(new CannotAcquireLockException("deadlock detected"))
                .thenReturn(7L);

        assertThat(facade.reverse(99L, KEY)).isEqualTo(7L);

        verify(ledger, times(2)).reverse(eq(99L), eq(KEY));
    }
}
