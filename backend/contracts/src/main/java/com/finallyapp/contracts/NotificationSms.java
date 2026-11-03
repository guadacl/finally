package com.finallyapp.contracts;

/**
 * Sends a notification as an SMS to its owner's phone number ({@link Notification#userId()}): the same types as
 * email ({@link NotificationEmailer#EMAILED}: CARD_CREATED, CARD_DELETED, TARGET_HIT; never CARD_MOVED).
 * Implemented by the app (phone lookup + notification MCP server tool {@code send_sms}); called by the workflow
 * module's {@code sms-notify} job worker and by the direct (Camunda-down) fallback, so each event is sent once.
 *
 * <p>Must be thread-safe, non-blocking and must never throw: other types, users without a phone number and
 * provider failures are skipped / logged by the implementation.
 */
public interface NotificationSms {

    static boolean isSent(NotificationType type) {
        return NotificationEmailer.isEmailed(type);
    }

    void send(Notification notification);
}
