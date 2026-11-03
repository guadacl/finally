package com.finallyapp.contracts;

import java.util.EnumSet;
import java.util.Set;

/**
 * Emails a notification to its owner ({@link Notification#userId()}): {@link NotificationType#CARD_CREATED},
 * {@link NotificationType#CARD_DELETED} and {@link NotificationType#TARGET_HIT} (never CARD_MOVED).
 * Implemented by the app (address lookup + email MCP server); called by the workflow module's
 * {@code email-notify} job worker and by the direct (Camunda-down) fallback, so each event is emailed once.
 *
 * <p>Must be thread-safe, non-blocking and must never throw: other types, users without an email address and
 * mail failures are skipped / logged by the implementation.
 */
public interface NotificationEmailer {

    /** The notification types that are emailed. */
    Set<NotificationType> EMAILED = EnumSet.of(
            NotificationType.CARD_CREATED, NotificationType.CARD_DELETED, NotificationType.TARGET_HIT);

    static boolean isEmailed(NotificationType type) {
        return type != null && EMAILED.contains(type);
    }

    void send(Notification notification);
}
