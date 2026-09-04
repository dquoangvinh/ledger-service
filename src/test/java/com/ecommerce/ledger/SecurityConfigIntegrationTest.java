package com.ecommerce.ledger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * That the security arrangement under test is the one that gets deployed.
 *
 * <p>It was not. {@code @EnableMethodSecurity} and the only {@code SecurityFilterChain} in the
 * project lived in a {@code TestSecurityConfig} under {@code src/test}; {@code src/main} had
 * neither. Every assertion about who may call what was therefore made against a configuration that
 * existed only while the suite ran, and the deployed service fell back on Spring Boot's default
 * chain - which protected the endpoints, but by a route nothing in the code named or checked.
 *
 * <p>These are deliberately blunt: is health reachable without a token, is the ledger not. They are
 * the two facts a deployment depends on, and neither was covered.
 */
@AutoConfigureMockMvc
@DisplayName("Security configuration")
class SecurityConfigIntegrationTest extends AbstractLedgerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("the health endpoint answers without a token")
    void healthIsReachableWithoutAToken() throws Exception {
        // Measured: this already held under Boot's default chain, so the permitAll in SecurityConfig
        // changes nothing today. It is asserted because a container healthcheck cannot present a
        // token, and the rest of that class could take this away by accident.
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("⭐ moving money without a token is refused with 401, not 403")
    void theLedgerIsNotOpen() throws Exception {
        /*
         * The status matters as much as the refusal. Removing SecurityConfig and running this test
         * yields 403, not 401: CSRF rejects the POST before authentication is considered at all. A
         * caller cannot act on that - 401 with WWW-Authenticate says "present a token", while 403
         * tells someone holding a perfectly good token to hunt for a permissions problem that does
         * not exist. This is the one assertion here that fails without the production config.
         */
        mockMvc.perform(post("/api/v1/ledger/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("reading a balance without a token is refused")
    void balancesAreNotOpenEither() throws Exception {
        mockMvc.perform(get("/api/v1/ledger/accounts/{id}/balance", java.util.UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("metrics stay behind authentication")
    void prometheusIsNotPublic() throws Exception {
        // Deliberate, and worth pinning: /actuator/prometheus names accounts and entry counts, which
        // describes the books to anyone who asks. Only health is public.
        int status = mockMvc.perform(get("/actuator/prometheus")).andReturn().getResponse().getStatus();
        assertThat(status)
                .as("prometheus is either absent or protected, never open")
                .isIn(401, 404);
    }
}
