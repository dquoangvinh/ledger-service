package com.ecommerce.ledger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point. Scheduling is on because LedgerMetricsRefresher republishes gauges on a timer.
 *
 * <p>The scan has to reach com.ecommerce.common as well: GlobalExceptionHandler lives there, and
 * without it a LedgerException carrying HttpStatus.FORBIDDEN escapes as a ServletException and
 * the caller sees 500 instead of 403.
 */
@SpringBootApplication(scanBasePackages = {"com.ecommerce.ledger", "com.ecommerce.common"})
@EnableScheduling
public class LedgerApplication {

    public static void main(String[] args) {
        SpringApplication.run(LedgerApplication.class, args);
    }
}
