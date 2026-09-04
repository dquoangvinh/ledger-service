package com.ecommerce.ledger.repository;

import com.ecommerce.ledger.model.JournalEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface JournalEntryRepository extends JpaRepository<JournalEntry, Long> {

    Optional<JournalEntry> findByIdempotencyKey(String idempotencyKey);

    boolean existsByIdempotencyKey(String idempotencyKey);

    /** Invariant I4 as an assertion: one key must never produce more than one entry. */
    long countByIdempotencyKey(String idempotencyKey);

    /** Has this entry already been reversed? Blocks reversing the same entry twice. */
    boolean existsByReversesId(Long reversesId);
}
