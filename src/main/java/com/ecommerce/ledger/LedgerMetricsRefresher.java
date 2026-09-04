package com.ecommerce.ledger;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Feeds the {@code ledger.system.total} gauge.
 *
 * <p>The gauge was registered from the start and never written to, so it read a flat zero for every
 * scrape. That is worse than having no metric at all: a missing series leaves a gap on the dashboard
 * and someone asks why, whereas a constant zero draws a clean line and looks like a system at rest.
 * Any alert built on it would have been meaningless.
 *
 * <p>Refreshed on a schedule rather than after each transfer. {@code totalSystem()} is an
 * unfiltered {@code SUM} over every balance row, and putting that on the write path would make each
 * transfer pay for a full-table aggregate. A gauge is a sampled value by nature, so sampling it is
 * the honest implementation.
 *
 * <p>Deliberately has no off switch, unlike the outbox poller: one aggregate a minute is not the
 * kind of background noise that gets in the way of a test.
 */
@Slf4j
@Component
public class LedgerMetricsRefresher {

    private final LedgerService ledger;
    private final LedgerMetrics metrics;

    public LedgerMetricsRefresher(LedgerService ledger, LedgerMetrics metrics) {
        this.ledger = ledger;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${app.ledger.metrics.refresh-ms:60000}")
    public void refresh() {
        try {
            metrics.setSystemTotal(ledger.totalSystem());
        } catch (RuntimeException e) {
            // A metric refresh must never take the service down, and a scheduled method that throws
            // is silently dropped by Spring - so the failure is logged rather than swallowed.
            log.warn("Could not refresh the ledger system total gauge", e);
        }
    }
}
