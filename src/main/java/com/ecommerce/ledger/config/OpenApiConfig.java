package com.ecommerce.ledger.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The API description, and the Authorize button that makes it usable.
 *
 * <p>Without the security scheme below, Swagger UI renders the endpoints and every "Try it out"
 * returns 401, because it has nowhere to put a token. Declaring the scheme gives the page an
 * Authorize box; declaring it as a top-level requirement marks every operation as needing it, which
 * is true - only health and this description are open.
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearer-jwt";

    @Bean
    public OpenAPI ledgerOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Double-entry ledger")
                        .version("v1")
                        .description("""
                                A money ledger whose rules live in PostgreSQL rather than in service \
                                code: postings are append-only, an entry is refused unless its debits \
                                and credits balance, and account balances are maintained by the \
                                database itself.

                                Every endpoint needs a bearer token issued by the project's \
                                auth-service. A transfer's source account must belong to the caller: \
                                the acting user is read from the signed token and never from the \
                                request body, so naming someone else's account is refused rather \
                                than honoured.

                                Transfers are idempotent on the key supplied with the request - \
                                repeating one produces the same entry, not a second movement of \
                                money.""")
                )
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Access token from auth-service: POST /api/v1/auth/login")));
    }
}
