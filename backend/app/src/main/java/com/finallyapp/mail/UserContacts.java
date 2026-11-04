package com.finallyapp.mail;

import com.finallyapp.persistence.UserProfileEntity;
import com.finallyapp.persistence.UserProfileRepository;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Contact details of each user ({@code users_profile.email}, {@code users_profile.phone}), so notifications can be
 * sent from background threads where there is no login / request context.
 *
 * <p>Both come from the Keycloak login (OIDC {@code email} and {@code phone_number} claims) and are written by
 * {@link UserContactSyncFilter} on the user's requests. Writes only happen when a value differs from the last one
 * this app instance stored, so the per-request cost is one map lookup.
 */
@Component
public class UserContacts {

    private static final Logger log = LoggerFactory.getLogger(UserContacts.class);

    /** E.164: '+', country code (no leading 0), 7-15 digits in total. */
    static final Pattern E164 = Pattern.compile("^\\+[1-9][0-9]{6,14}$");

    private final UserProfileRepository profiles;
    /** userId -> email / phone last written to the database by this instance. */
    private final Map<String, String> syncedEmail = new ConcurrentHashMap<>();
    private final Map<String, String> syncedPhone = new ConcurrentHashMap<>();

    public UserContacts(UserProfileRepository profiles) {
        this.profiles = profiles;
    }

    /** {@code +32 470 12-34-56} / {@code (+32) 470.123.456} -> {@code +32470123456}; null unless valid E.164. */
    public static String normalizePhone(String raw) {
        if (raw == null) {
            return null;
        }
        String compact = raw.replaceAll("[\\s().\\-/]", "");
        if (compact.startsWith("00")) {
            compact = "+" + compact.substring(2);
        }
        return E164.matcher(compact).matches() ? compact : null;
    }

    /** Stores the user's current email address (no-op when blank or unchanged). Never throws. */
    public void rememberEmail(String userId, String email) {
        if (userId == null || userId.isBlank() || email == null || email.isBlank()) {
            return;
        }
        String normalized = email.trim();
        if (normalized.equals(syncedEmail.get(userId))) {
            return;
        }
        try {
            // Only cache once the profile row exists (it is created at first login); otherwise retry next request.
            if (profiles.existsById(userId)) {
                if (profiles.updateEmail(userId, normalized) > 0) {
                    log.info("Stored email address for user {}", userId);
                }
                syncedEmail.put(userId, normalized);
            }
        } catch (RuntimeException e) {
            log.warn("Could not store email address for user {}: {}", userId, e.toString());
        }
    }

    /**
     * Stores the user's current phone number from the {@code phone_number} claim. A missing / blank claim clears a
     * stored number (the user removed it in Keycloak); an invalid one is ignored. Never throws.
     */
    public void rememberPhone(String userId, String rawPhone) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        String phone;
        if (rawPhone == null || rawPhone.isBlank()) {
            phone = "";
        } else {
            phone = normalizePhone(rawPhone);
            if (phone == null) {
                log.debug("Ignoring invalid phone number of user {} (not E.164)", userId);
                return;
            }
        }
        if (phone.equals(syncedPhone.get(userId))) {
            return;
        }
        try {
            String value = phone.isEmpty() ? null : phone;
            if (profiles.existsById(userId)) {
                if (profiles.updatePhone(userId, value) > 0) {
                    log.info(value == null ? "Cleared phone number of user {}" : "Stored phone number for user {}",
                            userId);
                }
                syncedPhone.put(userId, phone);
            }
        } catch (RuntimeException e) {
            log.warn("Could not store phone number for user {}: {}", userId, e.toString());
        }
    }

    /** The user's email address, if known. */
    public Optional<String> email(String userId) {
        return contact(userId, UserProfileEntity::getEmail);
    }

    /** The user's E.164 phone number, if known. */
    public Optional<String> phone(String userId) {
        return contact(userId, UserProfileEntity::getPhone);
    }

    private Optional<String> contact(String userId, Function<UserProfileEntity, String> field) {
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        return profiles.findById(userId)
                .map(field)
                .map(String::trim)
                .filter(v -> !v.isEmpty());
    }
}
