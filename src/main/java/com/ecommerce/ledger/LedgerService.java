package com.ecommerce.ledger;

import com.ecommerce.ledger.model.JournalEntry;
import com.ecommerce.ledger.model.LedgerAccount;
import com.ecommerce.ledger.model.LedgerAccountBalance;
import com.ecommerce.ledger.model.Posting;
import com.ecommerce.ledger.repository.JournalEntryRepository;
import com.ecommerce.ledger.repository.LedgerAccountBalanceRepository;
import com.ecommerce.ledger.repository.LedgerAccountRepository;
import com.ecommerce.ledger.repository.PostingRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The ledger itself: every movement of money is written as a balanced journal entry.
 *
 * <p>Balances are never edited in place by callers - they are a consequence of the postings, and
 * mistakes are fixed with a reversing entry rather than an UPDATE.
 */
@Slf4j
@Service
public class LedgerService {

    /**
     * Identifies the UNIQUE index on {@code ledger_journal_entry.idempotency_key}. PostgreSQL names
     * it {@code ledger_journal_entry_idempotency_key_key}, so matching on the column name survives
     * both the table prefix and a future rename that keeps the column in the constraint name.
     */
    private static final String IDEMPOTENCY_KEY_CONSTRAINT = "idempotency_key";

    /**
     * The partial unique index from V6 that lets an entry be reversed at most once. It fires when
     * two different idempotency keys race to reverse the same entry, which is a different failure
     * from replaying one key and has to be reported differently.
     */
    private static final String REVERSES_CONSTRAINT = "uq_ledger_journal_entry_reverses";

    private final LedgerAccountBalanceRepository balanceRepository;
    private final JournalEntryRepository entryRepository;
    private final PostingRepository postingRepository;
    private final LedgerAccountRepository accountRepository;
    private final LedgerMetrics metrics;
    private final MeterRegistry registry;

    public LedgerService(LedgerAccountBalanceRepository balanceRepository,
                         JournalEntryRepository entryRepository,
                         PostingRepository postingRepository,
                         LedgerAccountRepository accountRepository,
                         LedgerMetrics metrics,
                         MeterRegistry registry) {
        this.balanceRepository = balanceRepository;
        this.entryRepository = entryRepository;
        this.postingRepository = postingRepository;
        this.accountRepository = accountRepository;
        this.metrics = metrics;
        this.registry = registry;
    }

    /**
     * Transfer with an ownership check - this is the entry point the REST layer calls.
     *
     * <p>Authentication establishes who the caller is; it says nothing about whether they may touch
     * {@code fromAccountId}. Without this check an attacker holding a valid token simply changes
     * the account id in the request body (BOLA, OWASP API1:2023).
     *
     * <p>The check runs inside the transaction but before any row is locked, so an unauthorized
     * request is rejected without taking locks.
     */
    @Transactional
    public Long transferAs(UUID actingUserId, Long fromAccountId, Long toAccountId,
                           BigDecimal amount, String idempotencyKey) {
        if (actingUserId == null) {
            throw new LedgerException.NotAccountOwner("no authenticated principal");
        }
        if (!accountRepository.existsByIdAndOwnerUserId(fromAccountId, actingUserId)) {
            metrics.notOwner();
            // WARN, not INFO: this may be someone probing for BOLA. Log enough to
            // investigate and nothing more - no amounts, no balances.
            log.warn("Ledger authorization denied: principal={} attempted source account={}",
                    actingUserId, fromAccountId);
            // The message must not reveal whether the account exists, otherwise 403 versus 404
            // becomes an oracle for enumerating account ids.
            throw new LedgerException.NotAccountOwner("principal is not the owner of the source account");
        }
        return transfer(fromAccountId, toAccountId, amount, idempotencyKey);
    }

    /**
     * Transfer without an ownership check. For system work only - internal adjustments,
     * reconciliation jobs, test fixtures. The web layer must call {@link #transferAs} instead.
     */
    @Transactional
    public Long transfer(Long fromAccountId, Long toAccountId, BigDecimal amount, String idempotencyKey) {
        if (Objects.equals(fromAccountId, toAccountId)) {
            throw new LedgerException.SameAccount(fromAccountId);
        }
        if (amount == null || amount.signum() <= 0) {
            throw new LedgerException.InvalidAmount("amount must be greater than 0");
        }

        // try/finally so that every exit path is timed. Timing only the successful path
        // hides exactly the branches most likely to be slow.
        Timer.Sample sample = metrics.start(registry);
        try {
            // I4: a key that already exists returns the original result instead of moving money twice.
            Optional<JournalEntry> existing = entryRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                metrics.duplicateKey();
                log.info("Ledger idempotent replay: key={} returning existing entryId={}",
                        idempotencyKey, existing.get().getId());
                return existing.get().getId();
            }

            // The single most important line here: sort the ids before locking. Without it,
            // A-to-B and B-to-A running concurrently deadlock.
            List<Long> orderedIds = lockOrder(fromAccountId, toAccountId);
            Map<Long, LedgerAccountBalance> locked = balanceRepository.lockByIdsOrdered(orderedIds).stream()
                    .collect(Collectors.toMap(LedgerAccountBalance::getAccountId, Function.identity()));

            LedgerAccountBalance from = locked.get(fromAccountId);
            LedgerAccountBalance to = locked.get(toAccountId);
            if (from == null) {
                throw new LedgerException.AccountNotFound(fromAccountId);
            }
            if (to == null) {
                throw new LedgerException.AccountNotFound(toAccountId);
            }

            // Both orElseThrow branches are unreachable, and deliberately so. A balance row cannot
            // exist without its account - ledger_account_balance.account_id is the primary key and
            // a foreign key onto ledger_account(id) - and the two checks above already refused the
            // case where the balance row is missing. By this line the account provably exists.
            //
            // They stay because findById returns an Optional and the empty case has to be answered
            // by something; refusing with the same error the caller would otherwise get is the
            // honest answer. Worth knowing before chasing full branch coverage of this method:
            // these two cannot be reached, so 100% is not achievable here.
            LedgerAccount fromAccount = accountRepository.findById(fromAccountId)
                    .orElseThrow(() -> new LedgerException.AccountNotFound(fromAccountId));
            LedgerAccount toAccount = accountRepository.findById(toAccountId)
                    .orElseThrow(() -> new LedgerException.AccountNotFound(toAccountId));

            // Currency conversion is its own operation - two entries and an intermediate
            // account - not a side effect of a transfer.
            if (!fromAccount.getCurrency().equals(toAccount.getCurrency())) {
                throw new LedgerException.CurrencyMismatch(
                        fromAccount.getCurrency(), toAccount.getCurrency());
            }

            // Wallets only. Debiting the source and crediting the destination reads as "move money
            // across" solely for types that grow on credit. Against an ASSET the same postings run
            // the other way, and both guards below then say the opposite of what they mean: the
            // funds check refuses a transfer that would have raised the source, and the
            // destination is driven negative with nothing watching it.
            requireWallet(fromAccount);
            requireWallet(toAccount);

            if (from.getBalance().compareTo(amount) < 0) {
                metrics.insufficientFunds();
                log.info("Ledger transfer rejected: insufficient funds on account={}", fromAccountId);
                throw new LedgerException.InsufficientFunds(fromAccountId, from.getBalance(), amount);
            }

            JournalEntry entry = new JournalEntry(idempotencyKey, "transfer", null);
            entry.addPosting(new Posting(fromAccountId, Posting.Side.DEBIT, amount));
            entry.addPosting(new Posting(toAccountId, Posting.Side.CREDIT, amount));

            try {
                entryRepository.saveAndFlush(entry);
                // This line emits no SQL today. JournalEntry cascades PERSIST to its postings, and
                // both entities use GenerationType.IDENTITY - which makes Hibernate issue the INSERT
                // at persist time to obtain the key rather than deferring it to the flush - so the
                // rows are already written by the call above. Counting statements in the Hibernate
                // log for a single transfer gives exactly one entry insert and two posting inserts,
                // and no select: the merge here resolves against the managed instances.
                //
                // It stays so the service does not silently depend on that cascade. Drop
                // cascade = PERSIST from JournalEntry and this becomes the only thing writing the
                // postings; without it they would vanish and the imbalance would surface only at
                // COMMIT, when the deferred balance trigger fires.
                postingRepository.saveAllAndFlush(entry.getPostings());
            } catch (DataIntegrityViolationException violation) {
                // Only a clash on the idempotency key means "another caller already did this
                // work". Every other integrity violation here - the foreign keys on
                // ledger_posting, the CHECKs on side and amount - is a genuine failure, and
                // answering it with 409 Duplicate would tell the caller their transfer had
                // already succeeded when in fact it never ran.
                if (!isIdempotencyKeyClash(violation)) {
                    throw violation;
                }

                // Two threads got past the check above; the UNIQUE constraint is the second net.
                //
                // The entry id cannot be re-read here: after a constraint violation the Hibernate
                // session is broken and the transaction is marked rollback-only. That read has to
                // happen in a new transaction, which is what TransferFacade is for.
                metrics.duplicateKey();
                throw new LedgerException.DuplicateRequest(idempotencyKey);
            }

            // Apply accounting rules rather than always subtracting from the source:
            //   ASSET / EXPENSE grow on debit    -> debit adds
            //   everything else grows on credit  -> debit subtracts
            // Always subtracting is right for a customer wallet (LIABILITY) and wrong for
            // a platform cash account (ASSET).
            from.add(signedDelta(fromAccount, Posting.Side.DEBIT, amount));
            to.add(signedDelta(toAccount, Posting.Side.CREDIT, amount));

            metrics.posted();
            // Logged only after the entry is written, and without balances - those are sensitive.
            log.info("Ledger transfer posted: entryId={} from={} to={} amount={}",
                    entry.getId(), fromAccountId, toAccountId, amount);
            return entry.getId();
        } finally {
            metrics.record(sample);
        }
    }

    /**
     * Opens an account. The account and its balance row are created in one transaction, so the
     * "account with no balance" state never exists; the database trigger is the second guard.
     */
    @Transactional
    public Long openAccount(String accountNo, LedgerAccount.AccountType type, BigDecimal opening) {
        return openAccount(accountNo, type, opening, null);
    }

    @Transactional
    public Long openAccount(String accountNo, LedgerAccount.AccountType type,
                            BigDecimal opening, UUID ownerUserId) {
        if (opening == null || opening.signum() < 0) {
            throw new LedgerException.InvalidAmount("opening balance must be >= 0");
        }
        LedgerAccount account = accountRepository.saveAndFlush(
                new LedgerAccount(accountNo, type, ownerUserId));
        LedgerAccountBalance balance = balanceRepository.findById(account.getId())
                .orElseGet(() -> new LedgerAccountBalance(account.getId(), BigDecimal.ZERO));
        balance.add(opening);
        balanceRepository.saveAndFlush(balance);
        return account.getId();
    }

    /** Corrects a mistake by writing a mirrored entry; the original rows are left untouched. */
    @Transactional
    public Long reverse(Long entryId, String idempotencyKey) {
        Optional<JournalEntry> existing = entryRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return existing.get().getId();
        }

        JournalEntry target = entryRepository.findById(entryId)
                .orElseThrow(() -> new LedgerException.EntryNotFound(entryId));

        // Reversing a reversal is refused: fix a mistake by writing a correct entry, not by
        // bouncing back and forth. Allowing it produces unbounded chains and an unreadable ledger.
        if (target.getReversesId() != null) {
            throw new LedgerException.InvalidReversal(entryId,
                    "entry " + entryId + " is itself a reversal and cannot be reversed");
        }

        // Reversing the same entry twice is refused here so the caller gets a clear 409;
        // the unique index added in V6 is the last line of defence.
        if (entryRepository.existsByReversesId(entryId)) {
            throw new LedgerException.InvalidReversal(entryId,
                    "entry " + entryId + " has already been reversed");
        }

        List<Posting> original = postingRepository.findByEntryIdOrderByAccountId(entryId);
        if (original.isEmpty()) {
            throw new LedgerException.EntryNotFound(entryId);
        }

        List<Long> ids = original.stream().map(Posting::getAccountId).distinct().sorted().toList();
        Map<Long, LedgerAccountBalance> locked = balanceRepository.lockByIdsOrdered(ids).stream()
                .collect(Collectors.toMap(LedgerAccountBalance::getAccountId, Function.identity()));

        JournalEntry reversal = new JournalEntry(idempotencyKey, "reversal of " + entryId, entryId);
        for (Posting posting : original) {
            reversal.addPosting(new Posting(posting.getAccountId(), posting.getSide().flip(),
                    posting.getAmount()));
        }
        // Both checks above are made outside any lock, so two concurrent reversals can reach this
        // point together. The database indexes decide the winner, and the two ways they can fire
        // mean different things to the caller.
        try {
            entryRepository.saveAndFlush(reversal);
            postingRepository.saveAllAndFlush(reversal.getPostings());
        } catch (DataIntegrityViolationException violation) {
            String constraint = violatedConstraint(violation);
            if (constraint == null) {
                throw violation;
            }
            if (constraint.contains(IDEMPOTENCY_KEY_CONSTRAINT)) {
                // The same reversal request replayed concurrently. TransferFacade re-reads the
                // winner's entry id in a new transaction, so the caller still gets a result.
                metrics.duplicateKey();
                throw new LedgerException.DuplicateRequest(idempotencyKey);
            }
            if (constraint.contains(REVERSES_CONSTRAINT)) {
                // Two different keys reversing the same entry. Only one may win, and the loser
                // gets the same 409 the existsByReversesId check above would have produced.
                throw new LedgerException.InvalidReversal(entryId,
                        "entry " + entryId + " has already been reversed");
            }
            throw violation;
        }

        for (Posting posting : reversal.getPostings()) {
            LedgerAccountBalance balance = locked.get(posting.getAccountId());
            // Unreachable for the same reason as in transfer() above: ledger_posting.account_id is
            // NOT NULL and a foreign key onto ledger_account(id), so a posting cannot name an
            // account that does not exist.
            LedgerAccount account = accountRepository.findById(posting.getAccountId())
                    .orElseThrow(() -> new LedgerException.AccountNotFound(posting.getAccountId()));
            balance.add(signedDelta(account, posting.getSide(), posting.getAmount()));
        }
        log.info("Ledger entry reversed: original={} reversalEntryId={}", entryId, reversal.getId());
        return reversal.getId();
    }

    /** Reading a balance is object-level access too, so it is authorized the same way. */
    @Transactional(readOnly = true)
    public BigDecimal balanceAs(UUID actingUserId, Long accountId) {
        if (actingUserId == null
                || !accountRepository.existsByIdAndOwnerUserId(accountId, actingUserId)) {
            throw new LedgerException.NotAccountOwner("principal is not the owner of this account");
        }
        return balance(accountId);
    }

    @Transactional(readOnly = true)
    public BigDecimal balance(Long accountId) {
        return balanceRepository.findById(accountId)
                .orElseThrow(() -> new LedgerException.AccountNotFound(accountId))
                .getBalance();
    }

    @Transactional(readOnly = true)
    public BigDecimal totalSystem() {
        return balanceRepository.totalSystem();
    }

    /** Recomputes a balance from the postings, for reconciliation against the cached value. */
    @Transactional(readOnly = true)
    public BigDecimal derivedBalance(Long accountId) {
        return postingRepository.derivedBalance(accountId);
    }

    /** Invariant I1 as a query: must always return an empty list. */
    @Transactional(readOnly = true)
    public List<Long> unbalancedEntries() {
        return postingRepository.unbalancedEntryIds();
    }

    /**
     * True only when the violation is the UNIQUE index on the idempotency key.
     *
     * <p>The constraint name is taken from Hibernate's {@link ConstraintViolationException} rather
     * than parsed out of the message, which varies by driver and locale. A violation the extractor
     * cannot name counts as "not a duplicate": mislabelling an unknown failure as a duplicate is
     * the worse of the two errors, because it reports success for work that never happened.
     */
    private static boolean isIdempotencyKeyClash(DataIntegrityViolationException violation) {
        String constraint = violatedConstraint(violation);
        return constraint != null && constraint.contains(IDEMPOTENCY_KEY_CONSTRAINT);
    }

    /**
     * The name of the constraint that failed, lower-cased, or null when the driver did not name
     * one. Callers must treat null as "unknown failure" and let the exception through.
     */
    private static String violatedConstraint(DataIntegrityViolationException violation) {
        for (Throwable cause = violation; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException constraintViolation) {
                String name = constraintViolation.getConstraintName();
                return name != null ? name.toLowerCase(Locale.ROOT) : null;
            }
        }
        return null;
    }

    /**
     * Refuses anything that is not a customer wallet.
     *
     * <p>The chart of accounts still holds ASSET, EQUITY, REVENUE and EXPENSE accounts, and
     * {@code signedDelta} still posts to them correctly - but nothing reaches them through
     * {@code transfer}, whose debit-source/credit-destination shape is a wallet operation. Moving
     * money between a wallet and a platform account is a different entry with its own directions,
     * and it needs an API that takes the postings rather than inferring them.
     */
    private static void requireWallet(LedgerAccount account) {
        if (account.getAccountType() != LedgerAccount.AccountType.LIABILITY) {
            throw new LedgerException.NotAWalletAccount(account.getId(), account.getAccountType());
        }
    }

    /**
     * Turns one leg of an entry into a balance delta, according to the account type.
     * A customer wallet is a LIABILITY, so a debit lowers it.
     */
    private static BigDecimal signedDelta(LedgerAccount account, Posting.Side side, BigDecimal amount) {
        boolean increases = (side == Posting.Side.DEBIT) == account.getAccountType().increasesOnDebit();
        return increases ? amount : amount.negate();
    }

    /** Lock order: always ascending by account id, so concurrent opposite transfers cannot deadlock. */
    private static List<Long> lockOrder(Long first, Long second) {
        List<Long> ids = new ArrayList<>(List.of(first, second));
        Collections.sort(ids);
        return ids;
    }
}
