package com.ecommerce.ledger;

import com.ecommerce.ledger.model.JournalEntry;
import com.ecommerce.ledger.repository.JournalEntryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Thin layer that upholds the idempotency contract at the API boundary: the same key must always
 * come back with the same result, never an error. It is also where a lost deadlock is retried.
 *
 * <p>It has to be a separate bean rather than a method inside {@link LedgerService}:
 *
 * <ol>
 *   <li>Once the UNIQUE constraint fires, the Hibernate session is broken and the transaction is
 *       marked rollback-only. Re-reading the entry id in that same transaction throws
 *       "AssertionFailure: has a null identifier". The read must happen in a new transaction.
 *   <li>Wrapping it in {@code LedgerService} and calling {@code this.transfer()} would bypass the
 *       Spring proxy, which disables {@code @Transactional} entirely.
 * </ol>
 *
 * <p>Being a real bean means a real proxy, so the retry read runs in a fresh transaction and can
 * see what the winning thread just committed. Measured before this existed: 300 concurrent requests
 * with one key produced 9 clients receiving HTTP 409 instead of the entry id.
 */
@Slf4j
@Service
public class TransferFacade {

    /**
     * Three attempts, not more. A deadlock means someone else already succeeded, so a retry is
     * likely to find a clear path; retrying forever turns one unlucky pair into a system-wide
     * incident.
     */
    private static final int MAX_ATTEMPTS = 3;

    private static final long BACKOFF_BASE_MS = 20L;

    private final LedgerService ledger;
    private final JournalEntryRepository entryRepository;

    public TransferFacade(LedgerService ledger, JournalEntryRepository entryRepository) {
        this.ledger = ledger;
        this.entryRepository = entryRepository;
    }

    public Long transferAs(UUID actingUserId, Long fromAccountId, Long toAccountId,
                           BigDecimal amount, String idempotencyKey) {
        return attempt(idempotencyKey,
                () -> ledger.transferAs(actingUserId, fromAccountId, toAccountId, amount,
                        idempotencyKey));
    }

    public Long reverse(Long entryId, String idempotencyKey) {
        return attempt(idempotencyKey, () -> ledger.reverse(entryId, idempotencyKey));
    }

    /**
     * Runs the operation, absorbing the two failures that are not really failures.
     *
     * <p><b>Duplicate key.</b> Another caller got there first with the same key, so the answer is
     * that caller's entry id. It cannot be read inside the transaction that just died, which is the
     * whole reason this class exists.
     *
     * <p><b>Deadlock.</b> PostgreSQL resolves a deadlock by killing one of the transactions, and
     * the loser gets an exception for work that was perfectly valid. Retrying is the only correct
     * response, and it has to happen out here: by the time the exception surfaces, the inner
     * transaction is already rolled back, so there is nothing left to retry inside it.
     *
     * <p>The retry reuses the same idempotency key on purpose. A fresh key would make a second
     * transfer out of one request; the same key means that if the earlier attempt somehow did
     * commit, the retry lands on the duplicate branch above and returns its id.
     */
    private Long attempt(String idempotencyKey, Supplier<Long> operation) {
        PessimisticLockingFailureException lastDeadlock = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return operation.get();
            } catch (LedgerException.DuplicateRequest duplicate) {
                return entryRepository.findByIdempotencyKey(idempotencyKey)
                        .map(JournalEntry::getId)
                        .orElseThrow(() -> duplicate);
            } catch (PessimisticLockingFailureException deadlock) {
                lastDeadlock = deadlock;
                log.warn("Ledger lock contention on attempt {}/{} for key={}",
                        attempt, MAX_ATTEMPTS, idempotencyKey);
                if (attempt < MAX_ATTEMPTS) {
                    backOff(attempt);
                }
            }
        }

        // Out of attempts. Surfacing the original exception keeps the SQLSTATE and the server-side
        // detail, which is what anyone reading the log will need.
        throw lastDeadlock;
    }

    /**
     * Exponential, with jitter. Without the random part both losers of a deadlock wake at the same
     * moment and collide again, which turns a retry into a slower version of the same failure.
     */
    private static void backOff(int attempt) {
        long base = BACKOFF_BASE_MS << (attempt - 1);
        try {
            Thread.sleep(base + ThreadLocalRandom.current().nextLong(base));
        } catch (InterruptedException e) {
            // Restore the flag rather than swallow it: whoever interrupted this thread is entitled
            // to have the interruption observed further up.
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while backing off before a ledger retry", e);
        }
    }
}
