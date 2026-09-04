package com.ecommerce.ledger.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Who may call the ledger, and on whose authority.
 *
 * <p>This did not exist. There was a {@code TestSecurityConfig} under {@code src/test} carrying
 * {@code @EnableMethodSecurity} and a filter chain, and nothing at all under {@code src/main} - so
 * the suite exercised an arrangement that was never deployed.
 *
 * <p>{@code @PreAuthorize("isAuthenticated()")} on the controller therefore did nothing when the
 * application ran for real: Spring Boot does not enable method security by default, and nothing
 * else did either. The endpoints were not open - Boot's default chain still authenticated them -
 * but by a mechanism the code neither names nor checks, so the annotations could have been changed
 * to anything without a test noticing.
 *
 * <p>What the default chain actually got wrong, measured by removing this class and running
 * {@code SecurityConfigIntegrationTest}: an unauthenticated POST came back <b>403</b>, from CSRF
 * rejecting the request before authentication was ever considered. A client cannot act on that.
 * 401 with a {@code WWW-Authenticate} header says "present a token"; 403 says "you may not do this"
 * and invites a caller holding a perfectly good token to go looking for a permissions problem that
 * does not exist. Disabling CSRF - which a bearer-token API has no use for - is what turns it back
 * into the truth.
 *
 * <p>Health was already reachable without a token under the default chain, so pinning it here
 * changes nothing today; it is pinned because the rest of this class could easily have changed that
 * by accident.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final String jwkSetUri;
    private final String issuer;

    public SecurityConfig(@Value("${app.auth.jwk-set-uri}") String jwkSetUri,
                          @Value("${app.auth.issuer}") String issuer) {
        this.jwkSetUri = jwkSetUri;
        this.issuer = issuer;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                // A bearer-token API holds no session and reads no cookie, so there is no CSRF to
                // defend against and no session to create.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        // Only liveness. /actuator/prometheus stays behind authentication: it names
                        // accounts and entry counts, which is a description of the books to anyone
                        // who asks, and this service is meant to be reachable from the internet.
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.decoder(jwtDecoder())))
                .build();
    }

    /**
     * Keys come from the issuer's JWKS; the {@code iss} claim is checked against a plain string.
     *
     * <p>Not {@code issuer-uri}. That property makes Spring resolve the JWKS by OIDC discovery at
     * {@code {issuer}/.well-known/openid-configuration}, which needs the issuer to be a URL and the
     * issuer to serve a discovery document. The token signer here is this project's own auth-service,
     * whose issuer is the opaque string {@code ecommerce-auth-service} and which publishes a JWKS and
     * nothing else. Naming the JWKS directly skips discovery, and the issuer stays a string compared
     * for equality - the same arrangement the eight services in the parent project run on.
     */
    @Bean
    public JwtDecoder jwtDecoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
        return decoder;
    }
}
