package com.ecommerce.ledger.repository;

import com.ecommerce.ledger.model.LedgerAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface LedgerAccountRepository extends JpaRepository<LedgerAccount, Long> {

    Optional<LedgerAccount> findByAccountNo(String accountNo);

    /**
     * Object-level authorization check, which is what stops BOLA (OWASP API1:2023).
     *
     * <p>Authentication only says who the caller is; it says nothing about whether they own this
     * particular account. An attacker with a perfectly valid token can otherwise just change
     * fromAccountId in the request body.
     *
     * <p>Uses exists rather than loading the entity: the answer is only ever yes or no.
     */
    boolean existsByIdAndOwnerUserId(Long id, UUID ownerUserId);
}
