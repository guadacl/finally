package com.finallyapp.mail;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The email address of each user ({@code users_profile.email}), so alert emails can be sent from background
 * threads where there is no login / request context.
 *
 * <p>The address comes from the Keycloak login (OIDC {@code email} claim) and is written by
 * {@link UserEmailSyncFilter} on the user's requests. Writes only happen when the address differs from the
 * last one this app instance stored, so the per-request cost is one map lookup.
 */
@Component
public class UserEmails {

    private static final Logger log = LoggerFactory.getLogger(UserEmails.class);

    private final JdbcClient jdbc;
    /** userId -> email last written to the database by this instance. */
    private final Map<String, String> synced = new ConcurrentHashMap<>();

    public UserEmails(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Stores the user's current email address (no-op when blank or unchanged). Never throws. */
    public void remember(String userId, String email) {
        if (userId == null || userId.isBlank() || email == null || email.isBlank()) {
            return;
        }
        String normalized = email.trim();
        if (normalized.equals(synced.get(userId))) {
            return;
        }
        try {
            int rows = jdbc.sql("UPDATE users_profile SET email = :e WHERE id = :u AND email IS DISTINCT FROM :e")
                    .param("e", normalized).param("u", userId).update();
            Optional<String> stored = find(userId);
            // Only cache once the profile row exists (it is created at first login); otherwise retry next request.
            if (stored.isPresent() && stored.get().equals(normalized)) {
                synced.put(userId, normalized);
                if (rows > 0) {
                    log.info("Stored email address for user {}", userId);
                }
            }
        } catch (RuntimeException e) {
            log.warn("Could not store email address for user {}: {}", userId, e.toString());
        }
    }

    /** The user's email address, if known. */
    public Optional<String> find(String userId) {
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        return jdbc.sql("SELECT email FROM users_profile WHERE id = :u")
                .param("u", userId)
                .query(String.class)
                .optional()
                .map(String::trim)
                .filter(e -> !e.isEmpty());
    }
}
