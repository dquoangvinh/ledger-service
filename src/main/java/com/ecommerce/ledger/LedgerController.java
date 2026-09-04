package com.ecommerce.ledger;

import com.ecommerce.common.dto.ApiResponse;
import com.ecommerce.security.util.AuthenticationUtils;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ledger")
@RequiredArgsConstructor
public class LedgerController {

    private final LedgerService ledger;
    private final TransferFacade transfers;

    /**
     * The bounds here mirror the database columns. Without them an oversized value reaches
     * PostgreSQL and comes back as a 500, when it is really a client error and should be a 400.
     */
    public record TransferRequest(
            @NotNull Long fromAccountId,
            @NotNull Long toAccountId,
            // ledger_posting.amount is NUMERIC(20,4): 20 digits in total, 4 of them fractional.
            @NotNull
            @DecimalMin("0.0001")
            @Digits(integer = 16, fraction = 4)
            BigDecimal amount) {
    }

    /**
     * Moves money between two ledger accounts.
     *
     * <p>The acting user is taken from the signed token, never from the request body - reading it
     * from the body is how the vulnerability gets created rather than fixed.
     */
    @PostMapping("/transfers")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Map<String, Object>>> transfer(
            Authentication auth,
            // ledger_journal_entry.idempotency_key is VARCHAR(64); a longer key would otherwise
            // fail in the database as a 500 rather than being rejected here as a 400.
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 64) String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {

        UUID actingUserId = AuthenticationUtils.extractUserId(auth);
        Long entryId = transfers.transferAs(actingUserId, request.fromAccountId(),
                request.toAccountId(), request.amount(), idempotencyKey);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(Map.of("entryId", entryId, "status", "POSTED"),
                        "Transfer posted"));
    }

    /** Reading a balance is object-level access, so it is authorized the same way as a transfer. */
    @GetMapping("/accounts/{accountId}/balance")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Map<String, Object>>> balance(
            Authentication auth,
            @PathVariable Long accountId) {

        UUID actingUserId = AuthenticationUtils.extractUserId(auth);
        BigDecimal balance = ledger.balanceAs(actingUserId, accountId);
        return ResponseEntity.ok(ApiResponse.success(
                Map.of("accountId", accountId, "balance", balance)));
    }
}
