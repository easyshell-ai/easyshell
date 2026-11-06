package com.easyshell.server.security;

import org.springframework.security.core.Authentication;

/**
 * Helper for reading the acting user from the current {@link Authentication}.
 *
 * <p>The JWT filter stores the numeric user id as the principal (controllers cast it to
 * {@code Long}) and the username in the credentials slot, so {@code auth.getName()} would
 * return the id. Audit records must show the real username.
 */
public final class AuditUsers {

    private AuditUsers() {
    }

    public static String name(Authentication auth) {
        if (auth == null) {
            return "anonymous";
        }
        if (auth.getCredentials() instanceof String username && !username.isBlank()) {
            return username;
        }
        return auth.getName();
    }

    public static Long id(Authentication auth) {
        if (auth != null && auth.getPrincipal() instanceof Long userId) {
            return userId;
        }
        return null;
    }
}
