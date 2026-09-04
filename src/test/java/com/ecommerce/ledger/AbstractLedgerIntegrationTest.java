package com.ecommerce.ledger;

import com.ecommerce.ledger.model.LedgerAccount;
import com.ecommerce.ledger.repository.JournalEntryRepository;
import com.ecommerce.ledger.repository.PostingRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Shared setup for the ledger integration tests: a real PostgreSQL with the Flyway migrations
 * applied.
 *
 * <p>Real PostgreSQL is not a preference here. Two of the four invariants - entries must balance,
 * postings are append-only - are enforced by plpgsql triggers, and an in-memory database has
 * neither plpgsql nor deferrable constraint triggers. The locking behaviour that the concurrency
 * tests rely on only exists in a real engine as well.
 *
 * <p>The container lives in {@link LedgerContainers} as a {@code @Bean} carrying
 * {@code @ServiceConnection}, so Spring Boot derives {@code spring.datasource.*} from it and manages
 * its lifecycle. Because every ledger test shares this context configuration, the test context
 * cache starts one container for the whole suite rather than one per class.
 */
@SpringBootTest(properties = {
        // The shared "test" profile disables Flyway and lets Hibernate generate the schema, which
        // would produce tables with none of the ledger triggers - and a suite that passes for the
        // wrong reason. Both overrides are required.
        "spring.flyway.enabled=true",
        // validate rather than create-drop, so entity/schema drift fails the build too.
        "spring.jpa.hibernate.ddl-auto=validate",
        "app.security.oauth2.enabled=false",
        // The ledger publishes no events, so the test must not need a broker. Without this the
        // listeners would sit retrying against whatever is on localhost:9092 - which makes the
        // result depend on whether the developer happens to have the stack running.
        "spring.kafka.listener.auto-startup=false",
        // Nor does it need the outbox poller, which otherwise wakes every 500 ms and fires four
        // statements at outbox_events, burying the ledger's own SQL in the log.
        "app.outbox.poller.enabled=false"
})
@ActiveProfiles("test")
@Import(LedgerContainers.class)
abstract class AbstractLedgerIntegrationTest {

    @Autowired
    protected LedgerService ledger;

    @Autowired
    protected JournalEntryRepository entryRepository;

    @Autowired
    protected PostingRepository postingRepository;

    @Autowired
    protected JdbcTemplate jdbc;

    /**
     * Creates {@code count} wallet accounts and returns their ids.
     *
     * <p>Returning the ids matters: assuming they come out as 1, 2, 3 only holds while every test
     * truncates with RESTART IDENTITY, which silently couples the tests to each other.
     */
    protected List<Long> seedAccounts(int count, String opening) {
        truncateLedger();
        List<Long> ids = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            ids.add(ledger.openAccount(newAccountNo("ACC"),
                    LedgerAccount.AccountType.LIABILITY, new BigDecimal(opening)));
        }
        return ids;
    }

    /**
     * Unique account number that still fits {@code ledger_account.account_no VARCHAR(32)} - a full
     * UUID would be 36 characters on its own.
     */
    protected static String newAccountNo(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    protected void truncateLedger() {
        jdbc.execute("""
                TRUNCATE ledger_posting, ledger_journal_entry, ledger_account_balance, ledger_account
                RESTART IDENTITY CASCADE
                """);
    }

    protected static String newIdempotencyKey() {
        return UUID.randomUUID().toString();
    }
}
