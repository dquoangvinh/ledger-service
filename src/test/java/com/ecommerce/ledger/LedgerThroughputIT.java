package com.ecommerce.ledger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Invariant I2 - the pool of balances does not drift - measured under sustained load, and the
 * throughput figure that goes with it.
 *
 * <p>The accounts here are all of one type, which is what makes the sum a valid check: a transfer
 * between an ASSET and a LIABILITY raises both sides and the sum legitimately grows. I2 is
 * therefore about drift within a homogeneous pool, not about "money in the system", and
 * {@code LedgerInvariantsIntegrationTest#systemTotalIsPreservedByInternalMovement} covers the same
 * property in milliseconds so that it is checked on every build rather than only here.
 *
 * <p>Tagged {@code load} and excluded from the normal build. It runs ten thousand transfers and
 * takes tens of seconds, which is a load test rather than something every commit should pay for.
 * The correctness of concurrent access is already covered, quickly and deterministically, by
 * {@link LedgerConcurrencyIntegrationTest}.
 *
 * <p>Run it on demand:
 * <pre>./mvnw verify -pl services/payment-service -DexcludedGroups=</pre>
 */
@Tag("load")
class LedgerThroughputIT extends AbstractLedgerIntegrationTest {

    private static final int ACCOUNTS = 20;
    private static final int TRANSACTIONS = 10_000;
    private static final int THREADS = 50;

    @Test
    @DisplayName("I2 - system total is unchanged after 10,000 concurrent transfers")
    void systemTotalIsPreservedUnderTenThousandConcurrentTransfers() throws Exception {
        List<Long> accounts = seedAccounts(ACCOUNTS, "1000");
        BigDecimal before = ledger.totalSystem();

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        AtomicInteger posted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();

        List<Callable<Void>> tasks = new ArrayList<>(TRANSACTIONS);
        for (int i = 0; i < TRANSACTIONS; i++) {
            tasks.add(() -> {
                Long from = accounts.get(ThreadLocalRandom.current().nextInt(ACCOUNTS));
                Long to = accounts.get(ThreadLocalRandom.current().nextInt(ACCOUNTS));
                if (from.equals(to)) {
                    rejected.incrementAndGet();
                    return null;
                }
                try {
                    ledger.transfer(from, to, BigDecimal.ONE, newIdempotencyKey());
                    posted.incrementAndGet();
                } catch (LedgerException.InsufficientFunds | LedgerException.DuplicateRequest expected) {
                    // Both are correct outcomes rather than failures: the second means two threads
                    // raced past the idempotency check and the UNIQUE constraint caught it.
                    rejected.incrementAndGet();
                } catch (Exception e) {
                    unexpected.incrementAndGet();
                    if (unexpected.get() <= 3) {
                        System.out.println("   unexpected: " + e);
                    }
                }
                return null;
            });
        }

        long start = System.currentTimeMillis();
        pool.invokeAll(tasks);
        pool.shutdown();
        long elapsed = Math.max(System.currentTimeMillis() - start, 1);

        System.out.printf("%n[ledger] %d tx / %d threads / %d ms = %d tx/s | posted=%d rejected=%d errors=%d%n",
                TRANSACTIONS, THREADS, elapsed, TRANSACTIONS * 1000L / elapsed,
                posted.get(), rejected.get(), unexpected.get());

        // The three assertions that matter: no money created or destroyed, nothing failed for a
        // reason the ledger does not model, and every entry still balances.
        assertThat(ledger.totalSystem()).isEqualByComparingTo(before);
        assertThat(unexpected.get()).isZero();
        assertThat(ledger.unbalancedEntries()).isEmpty();
    }
}
