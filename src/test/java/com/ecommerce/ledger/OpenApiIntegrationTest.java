package com.ecommerce.ledger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * That the published API description is reachable, complete, and says how to authenticate.
 *
 * <p>A description behind the authentication it describes is no use to the person who needs it, and
 * a Swagger page with no Authorize button renders every operation as a 401 machine. Both are easy to
 * get wrong by omission and neither fails the build on its own, so they are asserted here.
 */
@AutoConfigureMockMvc
@DisplayName("API description")
class OpenApiIntegrationTest extends AbstractLedgerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("⭐ the description is readable without a token")
    void theDescriptionIsPublic() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Double-entry ledger"));
    }

    @Test
    @DisplayName("it lists both ledger operations")
    void bothOperationsAreDocumented() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/ledger/transfers'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/ledger/accounts/{accountId}/balance'].get").exists());
    }

    @Test
    @DisplayName("the transfer body is described with its real bounds")
    void theRequestSchemaCarriesItsConstraints() throws Exception {
        /*
         * springdoc logs a WARN at startup - "Cannot construct instance of java.util.HashSet ...
         * from String value ('string')" - while building example values. It is cosmetic: the
         * document itself is complete, which is what this asserts. Written down so the next person
         * to read that line does not go looking for a broken schema.
         *
         * The bounds come from the bean validation annotations, which is the point: the description
         * cannot drift from what the endpoint actually accepts, because it is generated from it.
         */
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.schemas.TransferRequest.properties.fromAccountId.format")
                        .value("int64"))
                .andExpect(jsonPath("$.components.schemas.TransferRequest.properties.amount.minimum")
                        .value(0.0001))
                .andExpect(jsonPath("$.components.schemas.TransferRequest.required")
                        .value(org.hamcrest.Matchers.containsInAnyOrder(
                                "fromAccountId", "toAccountId", "amount")));
    }

    @Test
    @DisplayName("⭐ it declares the bearer scheme, so the page can actually call the API")
    void theBearerSchemeIsDeclared() throws Exception {
        // Without this the Authorize box does not appear and every "Try it out" is a 401 with no
        // way for the reader to do anything about it.
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.securitySchemes['bearer-jwt'].scheme").value("bearer"))
                .andExpect(jsonPath("$.components.securitySchemes['bearer-jwt'].bearerFormat").value("JWT"));
    }

    @Test
    @DisplayName("the Swagger page itself is served")
    void theSwaggerPageIsServed() throws Exception {
        // springdoc answers /swagger-ui.html with a redirect to the bundled index.
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection());
    }
}
