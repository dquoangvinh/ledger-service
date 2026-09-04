package com.ecommerce.security.util;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.UUID;

public final class AuthenticationUtils {

    private AuthenticationUtils() {
        // Utility class
    }

    /**
     * Extract user ID from authentication.
     * Supports both JWT (OAuth2 mode) and String principal (Gateway-Trust mode).
     *
     * @param auth the authentication object
     * @return the user's UUID
     * @throws IllegalStateException if user ID cannot be extracted
     */
    public static UUID extractUserId(Authentication auth) {
        if (auth == null) {
            throw new IllegalStateException("Authentication is null");
        }

        Object principal = auth.getPrincipal();

        if (principal instanceof Jwt jwt) {
            return UUID.fromString(jwt.getSubject());
        } else if (principal instanceof String userId) {
            return UUID.fromString(userId);
        }

        throw new IllegalStateException("Unable to extract user ID from authentication: " + principal.getClass());
    }

    /**
     * Extract user ID as String from authentication.
     *
     * @param auth the authentication object
     * @return the user's ID as string
     */
    public static String extractUserIdString(Authentication auth) {
        if (auth == null) {
            throw new IllegalStateException("Authentication is null");
        }

        Object principal = auth.getPrincipal();

        if (principal instanceof Jwt jwt) {
            return jwt.getSubject();
        } else if (principal instanceof String userId) {
            return userId;
        }

        throw new IllegalStateException("Unable to extract user ID from authentication: " + principal.getClass());
    }

    /**
     * Extract email from authentication if available.
     *
     * @param auth the authentication object
     * @return the user's email, or null if not available
     */
    public static String extractEmail(Authentication auth) {
        if (auth == null) {
            return null;
        }

        Object principal = auth.getPrincipal();

        if (principal instanceof Jwt jwt) {
            return jwt.getClaimAsString("email");
        }

        return null;
    }

    /**
     * Extract preferred username from authentication if available.
     *
     * @param auth the authentication object
     * @return the user's preferred username, or null if not available
     */
    public static String extractUsername(Authentication auth) {
        if (auth == null) {
            return null;
        }

        Object principal = auth.getPrincipal();

        if (principal instanceof Jwt jwt) {
            return jwt.getClaimAsString("preferred_username");
        }

        return null;
    }

    /**
     * Check if authentication has admin role.
     *
     * @param auth the authentication object
     * @return true if admin
     */
    public static boolean isAdmin(Authentication auth) {
        if (auth == null) {
            return false;
        }

        // Check JWT claims for Keycloak realm_access
        if (auth.getPrincipal() instanceof Jwt jwt) {
            var realmAccess = jwt.getClaim("realm_access");
            if (realmAccess instanceof java.util.Map<?, ?> map) {
                var roles = map.get("roles");
                if (roles instanceof java.util.Collection<?> roleList) {
                    return roleList.contains("ADMIN");
                }
            }
        }

        // Check authorities (for Gateway-Trust mode)
        return auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
    }
}
