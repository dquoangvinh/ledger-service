package com.ecommerce.ledger.repository;

import com.ecommerce.ledger.model.LedgerAccountBalance;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface LedgerAccountBalanceRepository extends JpaRepository<LedgerAccountBalance, Long> {

    /**
     * Locks the given balance rows in ascending account id order.
     *
     * <p>The ordering is what prevents deadlock: with A-to-B and B-to-A running at the same time,
     * unordered locking gives "ERROR: deadlock detected", while both succeed once every transaction
     * takes its locks in the same order.
     *
     * <p>The ORDER BY is what actually fixes that order. LockRows always sits at the top of the
     * plan, so rows are locked after sorting rather than as they are scanned. Verified with
     * EXPLAIN: forcing a sequential scan gives "LockRows -> Sort -> Seq Scan" with the ORDER BY and
     * "LockRows -> Seq Scan" without it, and the latter locks in physical order, which is no order
     * at all. Dropping the ORDER BY looks safe today only because the planner happens to pick an
     * index scan on the primary key - that is a property of the current statistics, not of the
     * query, and it would reintroduce deadlocks the moment the plan changed.
     *
     * <p>The caller-side sort in {@code LedgerService.lockOrder} does not affect this query: IN is
     * a set, so the bind order carries no meaning. It is kept because it would become the deciding
     * factor if locking ever moved to one row per statement.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM LedgerAccountBalance b WHERE b.accountId IN :ids ORDER BY b.accountId")
    List<LedgerAccountBalance> lockByIdsOrdered(@Param("ids") List<Long> ids);

    /**
     * Invariant I2: the sum of every balance, which must not drift.
     *
     * <p>Narrower than the name suggests. It holds only while the accounts involved grow in the
     * same direction. A transfer between an ASSET and a LIABILITY raises both - a debit grows an
     * asset, a credit grows a liability - so this sum goes up by twice the amount. That is correct
     * double-entry, not money appearing from nowhere: solvency is Assets = Liabilities + Equity,
     * which a plain SUM cannot express.
     *
     * <p>Read it as "the pool of same-direction accounts has not drifted", not as "the books
     * balance". The books balancing is I1, and it is enforced by trg_ledger_entry_balance.
     */
    @Query("SELECT COALESCE(SUM(b.balance), 0) FROM LedgerAccountBalance b")
    BigDecimal totalSystem();
}
