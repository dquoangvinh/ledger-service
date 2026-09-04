package com.ecommerce.ledger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Request validation at the API boundary.
 *
 * <p>Every case here is a value the database would also reject. The point is that it comes back as
 * a 400 from validation rather than as a 500 from a constraint violation deep in the stack - if the
 * bounds on the request do not match the bounds on the column, PostgreSQL ends up doing the
 * validating, and it reports client mistakes as server errors.
 *
 * <p>{@link TransferFacade} and {@link LedgerService} are mocked: this is about the boundary, not
 * the ledger. It still boots the full context because the application class declares
 * {@code @EnableJpaRepositories} explicitly, which a {@code @WebMvcTest} slice cannot satisfy.
 */
@AutoConfigureMockMvc
class LedgerControllerValidationIntegrationTest extends AbstractLedgerIntegrationTest {

    private static final String VALID_BODY = """
            {"fromAccountId": 1, "toAccountId": 2, "amount": 100.0000}
            """;

    /**
     * AuthenticationUtils.extractUserId parses the token subject as a UUID, so the default jwt()
     * subject of "user" would blow up before any validation had a chance to run.
     */
    private static RequestPostProcessor userJwt() {
        return jwt().jwt(builder -> builder.subject(UUID.randomUUID().toString()));
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TransferFacade transfers;

    @MockitoBean
    private LedgerService ledgerService;

    @Test
    @DisplayName("A well-formed transfer is accepted")
    void validTransferIsAccepted() throws Exception {
        when(transfers.transferAs(any(), anyLong(), anyLong(), any(BigDecimal.class), anyString()))
                .thenReturn(42L);

        mockMvc.perform(post("/api/v1/ledger/transfers")
                        .with(userJwt())
                        .header("Idempotency-Key", "order-9001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("An idempotency key longer than the column is rejected with 400, not 500")
    void oversizedIdempotencyKeyIsRejected() throws Exception {
        // idempotency_key is VARCHAR(64), so 65 characters must never reach the database.
        String tooLong = "k".repeat(65);

        mockMvc.perform(post("/api/v1/ledger/transfers")
                        .with(userJwt())
                        .header("Idempotency-Key", tooLong)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isBadRequest());

        verify(transfers, never()).transferAs(any(), anyLong(), anyLong(), any(), anyString());
    }

    @Test
    @DisplayName("A blank idempotency key is rejected")
    void blankIdempotencyKeyIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/ledger/transfers")
                        .with(userJwt())
                        .header("Idempotency-Key", "   ")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isBadRequest());

        verify(transfers, never()).transferAs(any(), anyLong(), anyLong(), any(), anyString());
    }

    @Test
    @DisplayName("An amount with more digits than the column is rejected with 400, not 500")
    void oversizedAmountIsRejected() throws Exception {
        // amount is NUMERIC(20,4): at most 16 digits before the decimal point.
        String body = """
                {"fromAccountId": 1, "toAccountId": 2, "amount": 99999999999999999999}
                """;

        mockMvc.perform(post("/api/v1/ledger/transfers")
                        .with(userJwt())
                        .header("Idempotency-Key", "order-9002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verify(transfers, never()).transferAs(any(), anyLong(), anyLong(), any(), anyString());
    }

    @Test
    @DisplayName("An amount with more decimals than the column is rejected")
    void tooManyDecimalsIsRejected() throws Exception {
        String body = """
                {"fromAccountId": 1, "toAccountId": 2, "amount": 1.123456}
                """;

        mockMvc.perform(post("/api/v1/ledger/transfers")
                        .with(userJwt())
                        .header("Idempotency-Key", "order-9003")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("A non-positive amount is rejected")
    void nonPositiveAmountIsRejected() throws Exception {
        String body = """
                {"fromAccountId": 1, "toAccountId": 2, "amount": 0}
                """;

        mockMvc.perform(post("/api/v1/ledger/transfers")
                        .with(userJwt())
                        .header("Idempotency-Key", "order-9004")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }
}
