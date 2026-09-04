package com.ecommerce.ledger.repository;

import com.ecommerce.ledger.model.Posting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface PostingRepository extends JpaRepository<Posting, Long> {

    List<Posting> findByEntryIdOrderByAccountId(Long entryId);

    /**
     * Rebuilds a balance from the postings alone, independent of the cached balance table.
     * Reconciliation compares the two; any difference means the cache has drifted.
     *
     * <p>The direction has to follow the account type, exactly as {@code LedgerService.signedDelta}
     * does: assets and expenses grow on debit, everything else grows on credit. A fixed
     * credit-positive convention agrees with a customer wallet and returns the negation of the true
     * figure for a platform cash account - so reconciliation would report drift on every asset
     * account while nothing at all was wrong.
     *
     * <p>Native rather than JPQL for two reasons: Posting maps {@code accountId} as a plain column
     * rather than an association, so the account type is only reachable through an explicit join;
     * and PostgreSQL compares the two booleans directly, which keeps the expression a mirror of the
     * Java one instead of a nest of CASE branches.
     */
    @Query(value = """
            SELECT COALESCE(SUM(CASE
                    WHEN (p.side = 'D') = (a.account_type IN ('ASSET', 'EXPENSE'))
                    THEN p.amount ELSE -p.amount END), 0)
            FROM ledger_posting p
            JOIN ledger_account a ON a.id = p.account_id
            WHERE p.account_id = :accountId
            """, nativeQuery = true)
    BigDecimal derivedBalance(@Param("accountId") Long accountId);

    /** Invariant I1: entries whose debits and credits disagree. Must always be empty. */
    @Query("""
            SELECT p.entry.id FROM Posting p GROUP BY p.entry.id
            HAVING SUM(CASE WHEN p.side = 'D' THEN p.amount ELSE -p.amount END) <> 0
            """)
    List<Long> unbalancedEntryIds();
}
