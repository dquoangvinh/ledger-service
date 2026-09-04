package com.ecommerce.ledger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Correctness under contention. Every assertion here is exact, not statistical - a lost update or a
 * deadlock changes the number, so these fail loudly rather than flaking.
 *
 * <p>Kept separate from the throughput test in {@link LedgerThroughputIT}: these finish in seconds
 * and belong in every {@code mvn verify}, while measuring throughput does not.
 */
class LedgerConcurrencyIntegrationTest extends AbstractLedgerIntegrationTest {

    @Autowired
    private TransferFacade transfers;

    @Test
    @DisplayName("200 threads withdrawing from a balance of 100 produce exactly 100 transfers")
    void balanceNeverGoesNegativeUnderConcurrency() throws Exception {
        List<Long> accounts = seedAccounts(2, "0");
        Long source = accounts.get(0);
        Long target = accounts.get(1);
        jdbc.update("UPDATE ledger_account_balance SET balance = 100 WHERE account_id = ?", source);

        ExecutorService pool = Executors.newFixedThreadPool(40);
        AtomicInteger posted = new AtomicInteger();
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            tasks.add(() -> {
                try {
                    ledger.transfer(source, target, BigDecimal.ONE, newIdempotencyKey());
                    posted.incrementAndGet();
                } catch (LedgerException.InsufficientFunds ignored) {
                    // expected once the account is drained
                }
                return null;
            });
        }
        pool.invokeAll(tasks);
        pool.shutdown();

        // A lost update shows up here as more than 100 successes and a negative balance.
        assertThat(posted.get()).isEqualTo(100);
        assertThat(ledger.balance(source)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(ledger.unbalancedEntries()).isEmpty();
    }

    @Test
    @DisplayName("Opposite-direction transfers running together do not deadlock")
    void concurrentOppositeDirectionTransfersDoNotDeadlock() throws Exception {
        List<Long> accounts = seedAccounts(2, "100000");
        Long first = accounts.get(0);
        Long second = accounts.get(1);

        ExecutorService pool = Executors.newFixedThreadPool(40);
        List<String> errors = Collections.synchronizedList(new ArrayList<>());
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            boolean forward = i % 2 == 0;
            tasks.add(() -> {
                try {
                    ledger.transfer(forward ? first : second, forward ? second : first,
                            BigDecimal.ONE, newIdempotencyKey());
                } catch (Exception e) {
                    errors.add(e.getClass().getSimpleName() + ": " + e.getMessage());
                }
                return null;
            });
        }
        pool.invokeAll(tasks);
        pool.shutdown();

        // Without sorting the ids before locking, this list fills with "deadlock detected".
        assertThat(errors).isEmpty();
    }

    @Test
    @DisplayName("I4 - 1000 concurrent requests with one idempotency key create exactly one entry")
    void sameIdempotencyKeyCreatesExactlyOneEntry() throws Exception {
        List<Long> accounts = seedAccounts(2, "1000");
        Long source = accounts.get(0);
        Long target = accounts.get(1);
        String key = "IDEM-FIXED-" + newIdempotencyKey();

        ExecutorService pool = Executors.newFixedThreadPool(50);
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            tasks.add(() -> {
                try {
                    ledger.transfer(source, target, new BigDecimal("100"), key);
                } catch (Exception ignored) {
                    // losers of the race are expected; the UNIQUE constraint is the second net
                }
                return null;
            });
        }
        pool.invokeAll(tasks);
        pool.shutdown();

        assertThat(entryRepository.countByIdempotencyKey(key)).isEqualTo(1);
        // Money moved once, not a thousand times.
        assertThat(ledger.balance(source)).isEqualByComparingTo(new BigDecimal("900.0000"));
    }

    @Test
    @DisplayName("The idempotency contract holds through the facade: same key, same entry id")
    void facadeReturnsTheSameEntryIdForEveryConcurrentCaller() throws Exception {
        List<Long> accounts = seedAccounts(2, "1000");
        Long source = accounts.get(0);
        Long target = accounts.get(1);
        UUID owner = UUID.randomUUID();
        jdbc.update("UPDATE ledger_account SET owner_user_id = ? WHERE id = ?", owner, source);
        String key = "FACADE-" + newIdempotencyKey();

        ExecutorService pool = Executors.newFixedThreadPool(50);
        List<Callable<Long>> tasks = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            tasks.add(() -> transfers.transferAs(owner, source, target, new BigDecimal("10"), key));
        }
        List<Long> results = new ArrayList<>();
        for (Future<Long> future : pool.invokeAll(tasks)) {
            // Every caller must get a value, not an exception: the whole point of the facade is
            // that a duplicate key returns the winner's result instead of a 409.
            results.add(future.get());
        }
        pool.shutdown();

        assertThat(results).hasSize(300).doesNotContainNull();
        assertThat(Set.copyOf(results)).as("all callers see the same entry").hasSize(1);
        assertThat(entryRepository.countByIdempotencyKey(key)).isEqualTo(1);
    }

    /**
     * reverse() runs both of its guards - the reversal-of-a-reversal check and
     * existsByReversesId - outside any lock, so concurrent callers reach the insert together and
     * {@code uq_ledger_journal_entry_reverses} decides the winner. Distinct keys are what make this
     * test about that index and nothing else: the idempotency index cannot fire when every caller
     * brings its own key.
     *
     * <p>The losers must arrive as InvalidReversal. A raw DataIntegrityViolationException here
     * would mean the constraint was recognised as a failure but not translated, and the caller
     * would see 500 instead of 409.
     */
    @Test
    @DisplayName("Concurrent reversals of one entry, distinct keys, produce exactly one reversal")
    void concurrentReversalsWithDistinctKeysProduceExactlyOneReversal() throws Exception {
        List<Long> accounts = seedAccounts(2, "1000");
        Long source = accounts.get(0);
        Long entryId = ledger.transfer(source, accounts.get(1), new BigDecimal("400"),
                newIdempotencyKey());

        ExecutorService pool = Executors.newFixedThreadPool(25);
        AtomicInteger reversed = new AtomicInteger();
        List<String> unexpected = Collections.synchronizedList(new ArrayList<>());
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            tasks.add(() -> {
                try {
                    ledger.reverse(entryId, newIdempotencyKey());
                    reversed.incrementAndGet();
                } catch (LedgerException.InvalidReversal expected) {
                    // Either guard or the index - all three say the same thing to the caller.
                } catch (Exception e) {
                    unexpected.add(e.getClass().getSimpleName() + ": " + e.getMessage());
                }
                return null;
            });
        }
        pool.invokeAll(tasks);
        pool.shutdown();

        assertThat(unexpected).as("losers must be translated, not leaked").isEmpty();
        assertThat(reversed.get()).isEqualTo(1);
        assertThat(countReversalsOf(entryId)).isEqualTo(1);
        // Reversed once, so the source is back to its opening balance - not 1000 + 400 * n.
        assertThat(ledger.balance(source)).isEqualByComparingTo(new BigDecimal("1000.0000"));
        assertThat(ledger.unbalancedEntries()).isEmpty();
    }

    /**
     * The same reversal replayed concurrently. This deliberately asserts less than
     * {@code facadeReturnsTheSameEntryIdForEveryConcurrentCaller} does for transfers, because the
     * facade genuinely guarantees less here.
     *
     * <p>A transfer entry has {@code reverses_id} NULL, and the reversal index is partial
     * ({@code WHERE reverses_id IS NOT NULL}), so only the idempotency index can fire - always
     * DuplicateRequest, which the facade converts back into the winner's id. A reversal violates
     * <em>both</em> indexes at once, and PostgreSQL reports whichever it happens to check first, so
     * a caller can legitimately come back with InvalidReversal instead of an id.
     *
     * <p>Asserting "every caller gets the same id" would therefore be asserting a contract the
     * code does not provide, and would flake. What must hold either way is that one reversal
     * exists and no caller sees an untranslated database error.
     */
    @Test
    @DisplayName("Concurrent reversals sharing one key still produce exactly one reversal")
    void concurrentReversalsWithOneKeyProduceExactlyOneReversal() throws Exception {
        List<Long> accounts = seedAccounts(2, "1000");
        Long source = accounts.get(0);
        Long entryId = ledger.transfer(source, accounts.get(1), new BigDecimal("400"),
                newIdempotencyKey());
        String key = "REVERSAL-" + newIdempotencyKey();

        ExecutorService pool = Executors.newFixedThreadPool(25);
        List<Callable<Long>> tasks = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            tasks.add(() -> transfers.reverse(entryId, key));
        }
        List<Long> ids = new ArrayList<>();
        List<String> unexpected = new ArrayList<>();
        for (Future<Long> future : pool.invokeAll(tasks)) {
            try {
                ids.add(future.get());
            } catch (ExecutionException failure) {
                Throwable cause = failure.getCause();
                if (!(cause instanceof LedgerException.InvalidReversal)) {
                    unexpected.add(cause.getClass().getSimpleName() + ": " + cause.getMessage());
                }
            }
        }
        pool.shutdown();

        assertThat(unexpected).as("only InvalidReversal is an acceptable failure").isEmpty();
        assertThat(countReversalsOf(entryId)).isEqualTo(1);
        assertThat(entryRepository.countByIdempotencyKey(key)).isEqualTo(1);
        // The winner returns its own id, and every caller that did not hit the reversal index -
        // whether it short-circuited on the pre-check or came back through DuplicateRequest - is
        // handed that same id. So the set is exactly one value, however the 100 callers split.
        assertThat(ids).isNotEmpty();
        assertThat(Set.copyOf(ids)).hasSize(1);
        assertThat(ledger.balance(source)).isEqualByComparingTo(new BigDecimal("1000.0000"));
        assertThat(ledger.unbalancedEntries()).isEmpty();
    }

    /**
     * I2 under contention, with reversals in the mix. {@link LedgerThroughputIT} moves more money
     * but only ever transfers, so the path where a reversal and a transfer contend for the same
     * balance rows is never exercised anywhere else.
     *
     * <p>The pool is all LIABILITY - seedAccounts makes nothing else, and transfer() refuses
     * anything else - which is what makes SUM(balance) an invariant here at all.
     */
    @Test
    @DisplayName("Transfers and reversals running together leave the balance pool unchanged")
    void poolDoesNotDriftWhenTransfersAndReversalsInterleave() throws Exception {
        List<Long> accounts = seedAccounts(4, "1000");
        BigDecimal before = ledger.totalSystem();

        // Entries to aim reversals at, created up front so their ids are known.
        List<Long> reversible = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            reversible.add(ledger.transfer(accounts.get(i % 4), accounts.get((i + 1) % 4),
                    new BigDecimal("5"), newIdempotencyKey()));
        }

        ExecutorService pool = Executors.newFixedThreadPool(30);
        List<String> unexpected = Collections.synchronizedList(new ArrayList<>());
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            int index = i;
            tasks.add(() -> {
                try {
                    if (index % 2 == 0) {
                        ledger.transfer(accounts.get(index % 4), accounts.get((index + 2) % 4),
                                new BigDecimal("3"), newIdempotencyKey());
                    } else {
                        // Several threads aim at the same entry on purpose.
                        ledger.reverse(reversible.get(index % reversible.size()),
                                newIdempotencyKey());
                    }
                } catch (LedgerException.InvalidReversal | LedgerException.InsufficientFunds ok) {
                    // Both are ordinary outcomes of the race, and neither moves money.
                } catch (Exception e) {
                    unexpected.add(e.getClass().getSimpleName() + ": " + e.getMessage());
                }
                return null;
            });
        }
        pool.invokeAll(tasks);
        pool.shutdown();

        assertThat(unexpected).isEmpty();
        assertThat(ledger.totalSystem()).isEqualByComparingTo(before);
        assertThat(ledger.unbalancedEntries()).isEmpty();
        for (Long entryId : reversible) {
            assertThat(countReversalsOf(entryId)).as("entry %d", entryId).isLessThanOrEqualTo(1);
        }
    }

    /** Counted in SQL rather than through the repository so the test adds no production API. */
    private long countReversalsOf(Long entryId) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM ledger_journal_entry WHERE reverses_id = ?", Long.class, entryId);
        return count == null ? 0 : count;
    }
}
