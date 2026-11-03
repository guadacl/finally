package com.finallyapp.security;

import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The user whose data the current code path reads and writes ({@code user_id} in every table).
 *
 * <ul>
 *   <li>HTTP requests: the logged-in Keycloak user's subject ({@code sub} claim, the authentication name).</li>
 *   <li>Background jobs: whatever {@link #runAs} / {@link #callAs} set for the current thread.</li>
 *   <li>Authentication disabled ({@code finally.auth.enabled=false}): the legacy single user {@code "default"}.</li>
 * </ul>
 * There is deliberately no silent default when auth is enabled: code that runs without a user fails loudly
 * instead of mixing data between users.
 */
public final class CurrentUser {

    /** The pre-Keycloak single user; used only when authentication is disabled. */
    public static final String LEGACY_USER = "default";

    private static final ThreadLocal<String> OVERRIDE = new ThreadLocal<>();
    private static volatile boolean authEnabled = true;

    private CurrentUser() {
    }

    static void setAuthEnabled(boolean enabled) {
        authEnabled = enabled;
    }

    public static boolean isAuthEnabled() {
        return authEnabled;
    }

    /** The current user id; throws {@link IllegalStateException} if there is none. */
    public static String id() {
        return optionalId().orElseThrow(() -> new IllegalStateException("No authenticated user on this thread"));
    }

    public static Optional<String> optionalId() {
        String override = OVERRIDE.get();
        if (override != null) {
            return Optional.of(override);
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
            return Optional.ofNullable(auth.getName());
        }
        return authEnabled ? Optional.empty() : Optional.of(LEGACY_USER);
    }

    /** Runs {@code task} on this thread as {@code userId} (for scheduled jobs and other background work). */
    public static void runAs(String userId, Runnable task) {
        callAs(userId, () -> {
            task.run();
            return null;
        });
    }

    public static <T> T callAs(String userId, Supplier<T> task) {
        String previous = OVERRIDE.get();
        OVERRIDE.set(userId);
        try {
            return task.get();
        } finally {
            if (previous == null) {
                OVERRIDE.remove();
            } else {
                OVERRIDE.set(previous);
            }
        }
    }
}
