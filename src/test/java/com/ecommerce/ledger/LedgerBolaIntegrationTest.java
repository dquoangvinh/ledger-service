package com.ecommerce.ledger;

import com.ecommerce.ledger.model.LedgerAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Broken Object Level Authorization (OWASP API1:2023) over HTTP.
 *
 * <p>The attack needs no special tooling: sign in as yourself, then put someone else's account id
 * in {@code fromAccountId}. Authentication succeeds, the request is well-formed, and only an
 * ownership check standing between the caller and another person's balance decides what happens.
 *
 * <p>This goes through the real controller, the real service and a real database. The sibling
 * controller test mocks {@code TransferFacade}, which is right for validation but would mock away
 * the very check under test here.
 */
@AutoConfigureMockMvc
@DisplayName("BOLA over the transfer endpoint")
class LedgerBolaIntegrationTest extends AbstractLedgerIntegrationTest {

    private static final UUID ALICE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID BOB = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Autowired
    private MockMvc mockMvc;

    private Long aliceAccount;
    private Long bobAccount;

    @BeforeEach
    void seedTwoOwners() {
        truncateLedger();
        aliceAccount = ledger.openAccount(newAccountNo("ALICE"),
                LedgerAccount.AccountType.LIABILITY, new BigDecimal("100000"), ALICE);
        bobAccount = ledger.openAccount(newAccountNo("BOB"),
                LedgerAccount.AccountType.LIABILITY, new BigDecimal("0"), BOB);
    }

    private static RequestPostProcessor signedInAs(UUID userId) {
        return jwt().jwt(builder -> builder.subject(userId.toString()));
    }

    private String transferBody(Long from, Long to) {
        return """
                {"fromAccountId":%d,"toAccountId":%d,"amount":"50000"}""".formatted(from, to);
    }

    private BigDecimal balanceOf(Long accountId) {
        return jdbc.queryForObject(
                "SELECT balance FROM ledger_account_balance WHERE account_id = ?",
                BigDecimal.class, accountId);
    }

    @Test
    @DisplayName("Bob cannot move Alice's money by naming her account as the source")
    void changingTheSourceAccountIdIsRefused() throws Exception {
        BigDecimal aliceBefore = balanceOf(aliceAccount);

        mockMvc.perform(post("/api/v1/ledger/transfers")
                        .with(signedInAs(BOB))
                        .header("Idempotency-Key", "bola-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(aliceAccount, bobAccount)))
                .andExpect(status().isForbidden());

        // The status alone is not the guarantee - money must not have moved either.
        assertThat(balanceOf(aliceAccount))
                .as("Alice's balance must be untouched")
                .isEqualByComparingTo(aliceBefore);
        assertThat(balanceOf(bobAccount))
                .as("Bob must not have received anything")
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("the same request from the owner is accepted, so 403 is about ownership and nothing else")
    void theOwnerCanMakeTheIdenticalTransfer() throws Exception {
        mockMvc.perform(post("/api/v1/ledger/transfers")
                        .with(signedInAs(ALICE))
                        .header("Idempotency-Key", "owner-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(aliceAccount, bobAccount)))
                .andExpect(status().isCreated());

        assertThat(balanceOf(bobAccount))
                .as("the owner's transfer went through")
                .isEqualByComparingTo(new BigDecimal("50000"));
    }

    @Test
    @DisplayName("a refused attempt says nothing about whether the account exists")
    void refusalDoesNotLeakAccountExistence() throws Exception {
        // 403 for an account that exists and 403 for one that does not: telling them apart would
        // turn the endpoint into an oracle for enumerating account ids.
        mockMvc.perform(post("/api/v1/ledger/transfers")
                        .with(signedInAs(BOB))
                        .header("Idempotency-Key", "probe-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(999_999_999L, bobAccount)))
                .andExpect(status().isForbidden());
    }
}
