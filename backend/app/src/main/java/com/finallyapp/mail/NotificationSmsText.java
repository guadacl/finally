package com.finallyapp.mail;

import com.finallyapp.contracts.Notification;
import com.finallyapp.contracts.NotificationType;
import com.finallyapp.workflow.NotificationMessages;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Text of a notification SMS (at most {@link #MAX} characters, longer texts end with "…"):
 * <ul>
 *   <li>TARGET_HIT: {@code FinAlly: AAPL hit Support 185.00 (now 184.97)}</li>
 *   <li>CARD_CREATED: {@code FinAlly: new Support card AAPL @ 185.00}</li>
 *   <li>CARD_DELETED: {@code FinAlly: Support card AAPL @ 185.00 deleted}</li>
 * </ul>
 */
public final class NotificationSmsText {

    public static final int MAX = 160;

    private NotificationSmsText() {
    }

    public static String of(Notification n) {
        String ticker = n.ticker() == null ? "?" : n.ticker();
        String label = NotificationMessages.label(n.column());
        String target = money(n.targetPrice());
        NotificationType type = n.type() == null ? NotificationType.TARGET_HIT : n.type();
        String text = switch (type) {
            case TARGET_HIT -> "FinAlly: " + ticker + " hit " + label + " " + target
                    + (n.price() == null ? "" : " (now " + money(n.price()) + ")");
            case CARD_CREATED -> "FinAlly: new " + label + " card " + ticker + " @ " + target;
            case CARD_DELETED -> "FinAlly: " + label + " card " + ticker + " @ " + target + " deleted";
            case CARD_MOVED -> "FinAlly: " + ticker + " moved to " + label + " @ " + target; // never sent
        };
        return truncate(text);
    }

    /** Keeps at most {@link #MAX} characters; a cut text ends with "…". */
    public static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= MAX ? text : text.substring(0, MAX - 1) + "…";
    }

    private static String money(BigDecimal value) {
        return value == null ? "?" : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
