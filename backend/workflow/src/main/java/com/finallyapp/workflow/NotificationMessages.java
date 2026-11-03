package com.finallyapp.workflow;

import com.finallyapp.contracts.KanbanCardRef;
import com.finallyapp.contracts.KanbanColumn;
import com.finallyapp.contracts.Notification;
import com.finallyapp.contracts.NotificationType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/**
 * Human-readable notification texts. Used by both the {@code finally-notify} job worker and the
 * direct (Camunda-less) fallback path so the browser sees identical messages either way.
 */
public final class NotificationMessages {

    private NotificationMessages() {
    }

    /**
     * Examples:
     * <ul>
     *   <li>CARD_CREATED: {@code New Stop Loss card: AAPL @ 185.00}</li>
     *   <li>CARD_MOVED: {@code AAPL moved to Take Profit @ 200.00}</li>
     *   <li>TARGET_HIT: {@code AAPL hit stop loss 185.00 (now 184.97)}</li>
     * </ul>
     * Display names: column {@code SUPPORT} is shown as "Stop Loss", {@code RESISTANCE} as "Take Profit".
     */
    public static String format(NotificationType type, String ticker, KanbanColumn column,
                                BigDecimal targetPrice, BigDecimal price) {
        String t = ticker == null ? "?" : ticker;
        String col = label(column);
        return switch (type) {
            case CARD_CREATED -> "New " + col + " card: " + t + " @ " + money(targetPrice);
            case CARD_MOVED -> t + " moved to " + col + " @ " + money(targetPrice);
            case TARGET_HIT -> t + " hit " + col.toLowerCase(Locale.ROOT) + " " + money(targetPrice)
                    + (price == null ? "" : " (now " + money(price) + ")");
        };
    }

    /** Builds a complete notification for a card (random id, createdAt = now). */
    public static Notification build(NotificationType type, KanbanCardRef card, BigDecimal price) {
        return Notification.of(type, card, price,
                format(type, card.ticker(), card.column(), card.targetPrice(), price));
    }

    /** User-facing column name; the internal codes stay SUPPORT / RESISTANCE. */
    public static String label(KanbanColumn column) {
        if (column == null) {
            return "?";
        }
        return switch (column) {
            case SUPPORT -> "Stop Loss";
            case RESISTANCE -> "Take Profit";
        };
    }

    static String money(BigDecimal value) {
        return value == null ? "?" : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
