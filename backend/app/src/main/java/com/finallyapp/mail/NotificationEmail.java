package com.finallyapp.mail;

import com.finallyapp.contracts.KanbanColumn;
import com.finallyapp.contracts.Notification;
import com.finallyapp.contracts.NotificationType;
import com.finallyapp.workflow.NotificationMessages;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Subject and plain-text body of a notification email (CARD_CREATED, CARD_DELETED, TARGET_HIT).
 * Column codes are shown with their display names (Support / Resistance).
 *
 * <ul>
 *   <li>CARD_CREATED: {@code FinAlly: new Support card AAPL @ 185.00}</li>
 *   <li>CARD_DELETED: {@code FinAlly: Support card AAPL @ 185.00 deleted}</li>
 *   <li>TARGET_HIT: {@code FinAlly alert: AAPL hit Support 185.00}</li>
 * </ul>
 */
public record NotificationEmail(String subject, String body) {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);

    public static NotificationEmail of(Notification n, String alertsUrl) {
        String ticker = n.ticker() == null ? "?" : n.ticker();
        String label = NotificationMessages.label(n.column());
        String target = money(n.targetPrice());
        String time = TIME.format(n.createdAt() == null ? Instant.now() : n.createdAt());
        String rule = rule(n.column());
        NotificationType type = n.type() == null ? NotificationType.TARGET_HIT : n.type();

        return switch (type) {
            case TARGET_HIT -> new NotificationEmail(
                    "FinAlly alert: " + ticker + " hit " + label + " " + target,
                    ticker + " hit your " + label + " target.\n\n"
                            + details(ticker, label, rule, target, n.price(), time)
                            + footer(alertsUrl,
                            "You receive this email because a " + label + " card on your FinAlly board reached its target price.\n"
                                    + "No trade was executed. The card notifies again only after the price moves back across the target."));
            case CARD_CREATED -> new NotificationEmail(
                    "FinAlly: new " + label + " card " + ticker + " @ " + target,
                    "You added a " + label + " card for " + ticker + " to your FinAlly board.\n\n"
                            + details(ticker, label, rule, target, n.price(), time)
                            + footer(alertsUrl,
                            "You will get an email when the live price reaches the target (" + rule + ")."));
            case CARD_DELETED -> new NotificationEmail(
                    "FinAlly: " + label + " card " + ticker + " @ " + target + " deleted",
                    "You deleted the " + label + " card for " + ticker + " from your FinAlly board.\n\n"
                            + details(ticker, label, rule, target, n.price(), time)
                            + footer(alertsUrl, "There will be no more alerts for this card."));
            // never emailed; kept so the switch stays exhaustive
            case CARD_MOVED -> new NotificationEmail(
                    "FinAlly: " + ticker + " moved to " + label + " @ " + target,
                    details(ticker, label, rule, target, n.price(), time) + footer(alertsUrl, ""));
        };
    }

    private static String details(String ticker, String label, String rule, String target, BigDecimal price,
                                  String time) {
        return "Ticker:        " + ticker + "\n"
                + "Card:          " + label + " (" + rule + ")\n"
                + "Target price:  " + target + "\n"
                + (price == null ? "" : "Current price: " + money(price) + "\n")
                + "Time:          " + time + "\n";
    }

    private static String footer(String alertsUrl, String note) {
        return "\n"
                + "Open your alerts: " + alertsUrl + "\n"
                + "\n"
                + "-- \n"
                + (note.isEmpty() ? "" : note + "\n");
    }

    private static String rule(KanbanColumn column) {
        if (column == KanbanColumn.RESISTANCE) {
            return "alerts when price is at or above target";
        }
        if (column == KanbanColumn.SUPPORT) {
            return "alerts when price is at or below target";
        }
        return "target price";
    }

    private static String money(BigDecimal value) {
        return value == null ? "?" : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
