package com.ecommerce.ledger;

import com.ecommerce.ledger.model.LedgerAccount;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Correctness of the ledger with a single thread: the invariants, the accounting rules and the
 * authorization check.
 *
 * <p>Concurrency lives in {@link LedgerConcurrencyIntegrationTest}; throughput lives in
 * {@link LedgerThroughputIT}.
 */
class LedgerInvariantsIntegrationTest extends AbstractLedgerIntegrationTest {

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private LedgerMetricsRefresher metricsRefresher;

    @Test
    @DisplayName("I1 - every entry has debits equal to credits")
    void everyEntryIsBalanced() {
        List<Long> accounts = seedAccounts(2, "1000");
        ledger.transfer(accounts.get(0), accounts.get(1), new BigDecimal("100.0000"), newIdempotencyKey());

        assertThat(ledger.unbalancedEntries()).isEmpty();
    }

    /**
     * The only other cover for I2 is {@link LedgerThroughputIT}, which is tagged {@code load} and
     * excluded from the normal build - so without this test the invariant is never checked on a
     * commit. This one is the same property in milliseconds.
     *
     * <p>seedAccounts creates LIABILITY accounts only, and that is what makes the assertion valid:
     * the sum is invariant across a pool that moves in one direction. Mixing in an ASSET would
     * raise it on every transfer, correctly, as {@code debitDirectionFollowsAccountType} shows.
     */
    @Test
    @DisplayName("I2 - the balance pool does not drift across transfers and reversals")
    void systemTotalIsPreservedByInternalMovement() {
        List<Long> accounts = seedAccounts(3, "1000");
        BigDecimal before = ledger.totalSystem();

        ledger.transfer(accounts.get(0), accounts.get(1), new BigDecimal("250"), newIdempotencyKey());
        Long entryId = ledger.transfer(accounts.get(1), accounts.get(2), new BigDecimal("75.5000"),
                newIdempotencyKey());
        ledger.transfer(accounts.get(2), accounts.get(0), new BigDecimal("10.2500"), newIdempotencyKey());
        // A reversal moves money too, so it has to leave the pool untouched as well.
        ledger.reverse(entryId, newIdempotencyKey());

        assertThat(ledger.totalSystem()).isEqualByComparingTo(before);
    }

    @Test
    @DisplayName("I3 - postings cannot be updated or deleted")
    void postingsAreAppendOnly() {
        List<Long> accounts = seedAccounts(2, "1000");
        ledger.transfer(accounts.get(0), accounts.get(1), new BigDecimal("10"), newIdempotencyKey());

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE ledger_posting SET amount = 1 WHERE id = (SELECT min(id) FROM ledger_posting)"))
                .hasMessageContaining("append-only");

        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM ledger_posting WHERE id = (SELECT min(id) FROM ledger_posting)"))
                .hasMessageContaining("append-only");
    }

    @Test
    @DisplayName("I4 - replaying an idempotency key returns the original entry and moves no money")
    void replayingAnIdempotencyKeyIsANoOp() {
        List<Long> accounts = seedAccounts(2, "1000");
        String key = newIdempotencyKey();

        Long first = ledger.transfer(accounts.get(0), accounts.get(1), new BigDecimal("250"), key);
        Long replay = ledger.transfer(accounts.get(0), accounts.get(1), new BigDecimal("250"), key);

        assertThat(replay).isEqualTo(first);
        assertThat(entryRepository.countByIdempotencyKey(key)).isEqualTo(1);
        // Debited once, not twice.
        assertThat(ledger.balance(accounts.get(0))).isEqualByComparingTo(new BigDecimal("750.0000"));
    }

    @Test
    @DisplayName("A reversal restores the original balance and keeps both entries")
    void reversalRestoresOriginalBalance() {
        List<Long> accounts = seedAccounts(2, "1000");
        BigDecimal before = ledger.balance(accounts.get(0));

        Long entryId = ledger.transfer(accounts.get(0), accounts.get(1), new BigDecimal("500"),
                newIdempotencyKey());
        ledger.reverse(entryId, newIdempotencyKey());

        assertThat(ledger.balance(accounts.get(0))).isEqualByComparingTo(before);
        // Four postings, not two: history is added to, never rewritten.
        assertThat(postingRepository.count()).isEqualTo(4);
    }

    @Test
    @DisplayName("A reversal cannot itself be reversed, nor applied twice")
    void reversalsAreGuarded() {
        List<Long> accounts = seedAccounts(2, "1000");
        Long entryId = ledger.transfer(accounts.get(0), accounts.get(1), new BigDecimal("100"),
                newIdempotencyKey());
        Long reversalId = ledger.reverse(entryId, newIdempotencyKey());

        assertThatThrownBy(() -> ledger.reverse(reversalId, newIdempotencyKey()))
                .isInstanceOf(LedgerException.InvalidReversal.class)
                .hasMessageContaining("is itself a reversal");

        assertThatThrownBy(() -> ledger.reverse(entryId, newIdempotencyKey()))
                .isInstanceOf(LedgerException.InvalidReversal.class)
                .hasMessageContaining("already been reversed");
    }

    @Test
    @DisplayName("Reversing an entry that does not exist is refused with 404, not 500")
    void reversingAnUnknownEntryIsRefused() {
        seedAccounts(1, "1000");
        // truncateLedger restarts the identity sequence, so this is comfortably past the end.
        Long missing = 10_000L;

        assertThatThrownBy(() -> ledger.reverse(missing, newIdempotencyKey()))
                .isInstanceOf(LedgerException.EntryNotFound.class)
                .hasMessageContaining(String.valueOf(missing));

        // Nothing was written on the way to refusing.
        assertThat(entryRepository.count()).isZero();
        assertThat(postingRepository.count()).isZero();
    }

    /**
     * transfer debits the source and credits the destination, which only reads as "move money
     * across" for types that grow on credit. On an ASSET the same postings run the other way, and
     * both guards then say the opposite of what they mean: the funds check refuses a transfer that
     * would have raised the source, and the destination is driven negative unwatched. The operation
     * refuses the account rather than guessing.
     */
    @Test
    @DisplayName("Only wallets can transfer: a non-LIABILITY account is refused, on either side")
    void transferRefusesNonWalletAccounts() {
        truncateLedger();
        Long cash = ledger.openAccount(newAccountNo("CASH"),
                LedgerAccount.AccountType.ASSET, new BigDecimal("1000"));
        Long wallet = ledger.openAccount(newAccountNo("WALLET"),
                LedgerAccount.AccountType.LIABILITY, new BigDecimal("1000"));

        // As the source: the funds check would have passed and then raised the balance.
        assertThatThrownBy(() -> ledger.transfer(cash, wallet, new BigDecimal("100"),
                newIdempotencyKey()))
                .isInstanceOf(LedgerException.NotAWalletAccount.class)
                .hasMessageContaining("ASSET");

        // As the destination: nothing guards this side at all, so the asset would go to 900.
        assertThatThrownBy(() -> ledger.transfer(wallet, cash, new BigDecimal("100"),
                newIdempotencyKey()))
                .isInstanceOf(LedgerException.NotAWalletAccount.class)
                .hasMessageContaining("ASSET");

        // Refused before anything was written.
        assertThat(entryRepository.count()).isZero();
        assertThat(postingRepository.count()).isZero();
        assertThat(ledger.balance(cash)).isEqualByComparingTo(new BigDecimal("1000.0000"));
        assertThat(ledger.balance(wallet)).isEqualByComparingTo(new BigDecimal("1000.0000"));
    }

    @Test
    @DisplayName("Transfers between different currencies are refused")
    void currencyMismatchIsRefused() {
        List<Long> accounts = seedAccounts(2, "1000");
        jdbc.update("UPDATE ledger_account SET currency = 'USD' WHERE id = ?", accounts.get(1));

        assertThatThrownBy(() -> ledger.transfer(accounts.get(0), accounts.get(1),
                BigDecimal.ONE, newIdempotencyKey()))
                .isInstanceOf(LedgerException.CurrencyMismatch.class);
    }

    @Test
    @DisplayName("Transferring to the same account is refused")
    void sameAccountTransferIsRefused() {
        List<Long> accounts = seedAccounts(1, "1000");

        assertThatThrownBy(() -> ledger.transfer(accounts.get(0), accounts.get(0),
                BigDecimal.ONE, newIdempotencyKey()))
                .isInstanceOf(LedgerException.SameAccount.class);
    }

    @Test
    @DisplayName("A transfer naming an account that does not exist is refused, on either side")
    void unknownAccountIsRefused() {
        List<Long> accounts = seedAccounts(1, "1000");
        Long real = accounts.get(0);
        // truncateLedger restarts the identity sequence, so ids stay small and this is past the end.
        Long missing = real + 10_000L;

        // The source is checked first.
        assertThatThrownBy(() -> ledger.transfer(missing, real, BigDecimal.ONE, newIdempotencyKey()))
                .isInstanceOf(LedgerException.AccountNotFound.class)
                .hasMessageContaining(String.valueOf(missing));

        // The destination is checked too - a transfer into nowhere would leave the entry unbalanced.
        assertThatThrownBy(() -> ledger.transfer(real, missing, BigDecimal.ONE, newIdempotencyKey()))
                .isInstanceOf(LedgerException.AccountNotFound.class)
                .hasMessageContaining(String.valueOf(missing));

        // Reading is guarded the same way.
        assertThatThrownBy(() -> ledger.balance(missing))
                .isInstanceOf(LedgerException.AccountNotFound.class);

        // A refused transfer writes nothing: the entry is only saved after both lookups succeed,
        // and the rollback covers anything that slipped through.
        assertThat(entryRepository.count()).isZero();
        assertThat(postingRepository.count()).isZero();
        assertThat(ledger.balance(real)).isEqualByComparingTo(new BigDecimal("1000.0000"));
    }

    @Test
    @DisplayName("Ownership is checked per account, blocking BOLA")
    void transferRequiresOwnershipOfTheSourceAccount() {
        truncateLedger();
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        Long aliceAccount = ledger.openAccount(newAccountNo("ALICE"),
                LedgerAccount.AccountType.LIABILITY, new BigDecimal("1000"), alice);
        Long bobAccount = ledger.openAccount(newAccountNo("BOB"),
                LedgerAccount.AccountType.LIABILITY, new BigDecimal("0"), bob);

        // Bob holds a perfectly valid token and simply names Alice's account as the source.
        assertThatThrownBy(() -> ledger.transferAs(bob, aliceAccount, bobAccount,
                new BigDecimal("500"), newIdempotencyKey()))
                .isInstanceOf(LedgerException.NotAccountOwner.class);
        assertThat(ledger.balance(aliceAccount)).isEqualByComparingTo(new BigDecimal("1000.0000"));

        // Reading a balance is object-level access too.
        assertThatThrownBy(() -> ledger.balanceAs(bob, aliceAccount))
                .isInstanceOf(LedgerException.NotAccountOwner.class);

        // The owner is allowed through.
        ledger.transferAs(alice, aliceAccount, bobAccount, new BigDecimal("500"), newIdempotencyKey());
        assertThat(ledger.balance(aliceAccount)).isEqualByComparingTo(new BigDecimal("500.0000"));
    }

    @Test
    @DisplayName("An account cannot be opened with a negative or missing opening balance")
    void openingBalanceMustNotBeNegative() {
        truncateLedger();

        assertThatThrownBy(() -> ledger.openAccount(newAccountNo("NEG"),
                LedgerAccount.AccountType.LIABILITY, new BigDecimal("-0.0001")))
                .isInstanceOf(LedgerException.InvalidAmount.class)
                .hasMessageContaining("opening balance");

        // Null is refused rather than treated as zero: a caller that forgot the field is a bug,
        // not a request to open an empty account.
        assertThatThrownBy(() -> ledger.openAccount(newAccountNo("NULL"),
                LedgerAccount.AccountType.LIABILITY, null))
                .isInstanceOf(LedgerException.InvalidAmount.class);

        // Neither refusal leaves an account behind - a row here would also mean an orphan balance
        // row, because the trigger creates one for every account.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_account", Long.class)).isZero();

        // Zero is the boundary and is allowed: an empty wallet is a normal thing to open.
        Long id = ledger.openAccount(newAccountNo("ZERO"),
                LedgerAccount.AccountType.LIABILITY, BigDecimal.ZERO);
        assertThat(ledger.balance(id)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("Every account gets a balance row, even when inserted by hand")
    void everyAccountAutomaticallyGetsABalanceRow() {
        truncateLedger();

        Long id = jdbc.queryForObject("""
                INSERT INTO ledger_account(account_no, account_type)
                VALUES ('SOLO', 'LIABILITY') RETURNING id
                """, Long.class);

        Long balanceRows = jdbc.queryForObject(
                "SELECT count(*) FROM ledger_account_balance WHERE account_id = ?", Long.class, id);

        assertThat(balanceRows).as("the trigger must create the balance row").isEqualTo(1L);
        assertThat(ledger.balance(id)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("Cached balances match the balance derived from postings")
    void cachedBalanceMatchesSumOfPostings() {
        int count = 10;
        List<Long> accounts = seedAccounts(count, "1000");

        for (int i = 0; i < 300; i++) {
            long from = accounts.get(ThreadLocalRandom.current().nextInt(count));
            long to = accounts.get(ThreadLocalRandom.current().nextInt(count));
            if (from == to) {
                continue;
            }
            try {
                ledger.transfer(from, to, new BigDecimal("7.3300"), newIdempotencyKey());
            } catch (LedgerException.InsufficientFunds ignored) {
                // expected once an account runs dry
            }
        }

        for (Long id : accounts) {
            BigDecimal cached = ledger.balance(id);
            // derivedBalance covers postings only, so add the opening balance back.
            BigDecimal derived = ledger.derivedBalance(id).add(new BigDecimal("1000.0000"));
            assertThat(cached).as("account %d", id).isEqualByComparingTo(derived);
        }
    }

    /**
     * derivedBalance still has to be right for every account type even though {@code transfer} now
     * only touches wallets: it is the reconciliation query, and the chart of accounts keeps its
     * ASSET, EQUITY, REVENUE and EXPENSE rows. A general posting API would reach them, and this is
     * what says the arithmetic will be ready when it does.
     *
     * <p>The entry is written straight to the tables because {@code transfer} refuses an ASSET.
     * Both legs go in one statement: the balance trigger is deferred to COMMIT, and a single-leg
     * INSERT would commit on its own and be rejected as unbalanced.
     */
    @Test
    @DisplayName("Derived balance follows the account type, not a fixed sign convention")
    void derivedBalanceFollowsAccountType() {
        truncateLedger();
        Long cash = ledger.openAccount(newAccountNo("CASH"),
                LedgerAccount.AccountType.ASSET, BigDecimal.ZERO);
        Long wallet = ledger.openAccount(newAccountNo("WALLET"),
                LedgerAccount.AccountType.LIABILITY, BigDecimal.ZERO);

        Long entryId = jdbc.queryForObject("""
                INSERT INTO ledger_journal_entry(idempotency_key, description)
                VALUES (?, 'customer funds a wallet with cash') RETURNING id
                """, Long.class, newIdempotencyKey());
        jdbc.update("""
                INSERT INTO ledger_posting(entry_id, account_id, side, amount)
                VALUES (?, ?, 'D', 100.0000), (?, ?, 'C', 100.0000)
                """, entryId, cash, entryId, wallet);

        // A debit raises an asset. The fixed credit-positive convention this replaced returned
        // -100 here, so reconciliation reported drift on every asset account while nothing at all
        // was wrong.
        assertThat(ledger.derivedBalance(cash))
                .as("ASSET grows on debit")
                .isEqualByComparingTo(new BigDecimal("100.0000"));

        // The liability grows on credit, so it runs the other way round and lands on the same sign.
        assertThat(ledger.derivedBalance(wallet))
                .as("LIABILITY grows on credit")
                .isEqualByComparingTo(new BigDecimal("100.0000"));
    }

    /**
     * Regression guard for the branch added in 6f453baf. Before it, every
     * DataIntegrityViolationException coming out of the two saves was reported as
     * DuplicateRequest - so a foreign key or CHECK failure told the caller their transfer had
     * already succeeded when it had never run at all. Delete the {@code throw violation} and this
     * is the test that notices.
     *
     * <p>The violation has to be manufactured. Nothing reachable through {@code transfer} can break
     * any other constraint: the foreign keys are satisfied by the lookups above it, {@code side} is
     * always D or C, and {@code amount > 0} is guarded before the insert. A temporary CHECK is the
     * smallest way in, and it can only be added while ledger_posting is empty - which
     * {@code seedAccounts} guarantees, since it truncates first.
     */
    @Test
    @DisplayName("An integrity violation that is not a duplicate key is not reported as one")
    void nonDuplicateIntegrityViolationIsNotReportedAsDuplicate() {
        List<Long> accounts = seedAccounts(2, "1000");
        jdbc.execute("ALTER TABLE ledger_posting ADD CONSTRAINT tmp_force_violation CHECK (amount < 0)");
        try {
            assertThatThrownBy(() -> ledger.transfer(accounts.get(0), accounts.get(1),
                    new BigDecimal("10"), newIdempotencyKey()))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .isNotInstanceOf(LedgerException.DuplicateRequest.class);
        } finally {
            // In a finally so a failed assertion cannot leave the constraint behind and take every
            // later test in the class down with it.
            jdbc.execute("ALTER TABLE ledger_posting DROP CONSTRAINT tmp_force_violation");
        }

        assertThat(entryRepository.count()).isZero();
        assertThat(postingRepository.count()).isZero();
    }

    /**
     * {@code AuthenticationUtils.extractUserId} returns null when the token carries no usable
     * subject, and that null arrives here. Without this branch an unauthenticated caller would fall
     * through to the ownership query, which cannot match a null owner and would refuse for the
     * wrong reason - or, if the query were ever relaxed, would not refuse at all.
     */
    @Test
    @DisplayName("A transfer with no authenticated principal is refused")
    void transferWithoutPrincipalIsRefused() {
        List<Long> accounts = seedAccounts(2, "1000");

        assertThatThrownBy(() -> ledger.transferAs(null, accounts.get(0), accounts.get(1),
                new BigDecimal("10"), newIdempotencyKey()))
                .isInstanceOf(LedgerException.NotAccountOwner.class)
                .hasMessageContaining("no authenticated principal");

        assertThat(entryRepository.count()).isZero();
    }

    /**
     * The HTTP layer already rejects these through {@code @DecimalMin} on the request record, so
     * this looks redundant - but {@code transfer} is public and documented for internal
     * adjustments, reconciliation jobs and fixtures. Those callers never touch a controller, and
     * for them the guard inside the service is the only one there is.
     */
    @Test
    @DisplayName("transfer refuses a null or non-positive amount, with no controller involved")
    void transferRefusesInvalidAmounts() {
        List<Long> accounts = seedAccounts(2, "1000");

        assertThatThrownBy(() -> ledger.transfer(accounts.get(0), accounts.get(1), null,
                newIdempotencyKey()))
                .as("null amount")
                .isInstanceOf(LedgerException.InvalidAmount.class);

        assertThatThrownBy(() -> ledger.transfer(accounts.get(0), accounts.get(1), BigDecimal.ZERO,
                newIdempotencyKey()))
                .as("zero amount")
                .isInstanceOf(LedgerException.InvalidAmount.class);

        assertThatThrownBy(() -> ledger.transfer(accounts.get(0), accounts.get(1),
                new BigDecimal("-0.0001"), newIdempotencyKey()))
                .as("negative amount")
                .isInstanceOf(LedgerException.InvalidAmount.class);

        assertThat(entryRepository.count()).isZero();
    }

    /**
     * The funds guard is proven today only as a side effect of
     * {@code balanceNeverGoesNegativeUnderConcurrency}: 200 threads draw on a balance of 100 and
     * exactly 100 succeed, which cannot happen unless the guard works. That is real evidence, but
     * when it goes red it does not say whether the guard or the locking broke. This states the
     * guard on its own, single-threaded, and pins the boundary either side of it.
     */
    @Test
    @DisplayName("A transfer larger than the balance is refused; exactly the balance is allowed")
    void insufficientFundsIsRefusedAtTheBoundary() {
        List<Long> accounts = seedAccounts(2, "1000");
        Long source = accounts.get(0);
        Long target = accounts.get(1);

        assertThatThrownBy(() -> ledger.transfer(source, target, new BigDecimal("1000.0001"),
                newIdempotencyKey()))
                .isInstanceOf(LedgerException.InsufficientFunds.class);

        // Refused, and nothing moved on either side.
        assertThat(ledger.balance(source)).isEqualByComparingTo(new BigDecimal("1000.0000"));
        assertThat(ledger.balance(target)).isEqualByComparingTo(new BigDecimal("1000.0000"));
        assertThat(entryRepository.count()).isZero();

        // The other side of the boundary: draining the account exactly is allowed, so the guard is
        // "less than", not "less than or equal".
        ledger.transfer(source, target, new BigDecimal("1000.0000"), newIdempotencyKey());
        assertThat(ledger.balance(source)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(ledger.balance(target)).isEqualByComparingTo(new BigDecimal("2000.0000"));
    }

    /**
     * The gauge existed from the start and nothing ever wrote to it, so it reported a flat zero on
     * every scrape - a clean line on the dashboard that looked like a system at rest. This asserts
     * two things a constant would also satisfy separately but cannot satisfy together: that the
     * gauge holds the real total, and that it changes when the total does.
     */
    @Test
    @DisplayName("The system-total gauge reports the ledger rather than a constant")
    void systemTotalGaugeTracksTheLedger() {
        seedAccounts(2, "1000");
        metricsRefresher.refresh();

        assertThat(meterRegistry.get("ledger.system.total").gauge().value())
                .as("two wallets of 1000")
                .isEqualTo(2000.0d);

        // A transfer between wallets cannot move this - that is I2. Opening an account can.
        ledger.openAccount(newAccountNo("EXTRA"),
                LedgerAccount.AccountType.LIABILITY, new BigDecimal("500"));
        metricsRefresher.refresh();

        assertThat(meterRegistry.get("ledger.system.total").gauge().value())
                .as("after opening a third wallet of 500")
                .isEqualTo(2500.0d);
    }
}
