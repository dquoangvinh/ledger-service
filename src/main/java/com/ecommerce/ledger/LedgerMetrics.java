package com.ecommerce.ledger;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Business metrics for the ledger.
 *
 * <p>Meter names use dots, following Micrometer convention; the Prometheus registry renders
 * {@code ledger.transfers} as {@code ledger_transfers_total}.
 *
 * <p>Account ids and user ids are never used as tags. Each distinct tag value creates its own time
 * series, so a per-account tag would grow unbounded and take Prometheus down with it.
 */
@Component
public class LedgerMetrics {

    private final Counter transfersPosted;
    private final Counter transfersInsufficientFunds;
    private final Counter transfersNotOwner;
    private final Counter transfersDuplicate;
    private final Timer transferTimer;
    private final AtomicReference<BigDecimal> systemTotal = new AtomicReference<>(BigDecimal.ZERO);

    public LedgerMetrics(MeterRegistry registry) {
        this.transfersPosted = Counter.builder("ledger.transfers")
                .description("Transfers written to the ledger")
                .tag("outcome", "posted")
                .register(registry);

        this.transfersInsufficientFunds = Counter.builder("ledger.transfers")
                .tag("outcome", "insufficient_funds")
                .register(registry);

        this.transfersNotOwner = Counter.builder("ledger.transfers")
                .tag("outcome", "not_owner")
                .register(registry);

        this.transfersDuplicate = Counter.builder("ledger.transfers")
                .tag("outcome", "duplicate_key")
                .register(registry);

        this.transferTimer = Timer.builder("ledger.transfer.duration")
                .description("Time to complete one transfer")
                // Histogram buckets rather than client-side quantiles: buckets can be summed
                // across instances, and the average of two p99s is not a p99.
                .publishPercentileHistogram()
                .register(registry);

        // Invariant I2 as a gauge: drift from the expected total is an alert. Only meaningful
        // while the accounts move in the same direction - a transfer between an ASSET and a
        // LIABILITY legitimately raises this sum, because a debit grows an asset and a credit
        // grows a liability. Alert on unexplained movement, not on movement.
        Gauge.builder("ledger.system.total", systemTotal, ref -> ref.get().doubleValue())
                .description("Sum of every account balance; constant only across same-direction accounts (invariant I2)")
                .register(registry);
    }

    public void posted() {
        transfersPosted.increment();
    }

    public void insufficientFunds() {
        transfersInsufficientFunds.increment();
    }

    public void notOwner() {
        transfersNotOwner.increment();
    }

    public void duplicateKey() {
        transfersDuplicate.increment();
    }

    public Timer.Sample start(MeterRegistry registry) {
        return Timer.start(registry);
    }

    public void record(Timer.Sample sample) {
        sample.stop(transferTimer);
    }

    public void setSystemTotal(BigDecimal total) {
        systemTotal.set(total);
    }
}
