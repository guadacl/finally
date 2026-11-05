package com.finallyapp.mail;

import static org.assertj.core.api.Assertions.assertThat;

import com.finallyapp.contracts.KanbanColumn;
import com.finallyapp.contracts.Notification;
import com.finallyapp.contracts.NotificationType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NotificationEmailTest {

    private static Notification hit(KanbanColumn column, String target, String price) {
        return new Notification(UUID.randomUUID(), NotificationType.TARGET_HIT, UUID.randomUUID(), "AAPL", column,
                new BigDecimal(target), price == null ? null : new BigDecimal(price), "msg",
                Instant.parse("2026-11-03T10:15:30Z"), "user-1");
    }

    @Test
    void supportEmail() {
        NotificationEmail e = NotificationEmail.of(hit(KanbanColumn.SUPPORT, "185", "184.966"), "http://localhost:8000/alerts");
        assertThat(e.subject()).isEqualTo("Tradex alert: AAPL hit Support 185.00");
        assertThat(e.body())
                .contains("Ticker:        AAPL")
                .contains("Card:          Support (alerts when price is at or below target)")
                .contains("Target price:  185.00")
                .contains("Current price: 184.97")
                .contains("Time:          2026-11-03 10:15:30 UTC")
                .contains("http://localhost:8000/alerts")
                .doesNotContain("SUPPORT");
    }

    @Test
    void resistanceEmailUsesDisplayName() {
        NotificationEmail e = NotificationEmail.of(hit(KanbanColumn.RESISTANCE, "200.5", "201"), "http://x/alerts");
        assertThat(e.subject()).isEqualTo("Tradex alert: AAPL hit Resistance 200.50");
        assertThat(e.body()).contains("Resistance (alerts when price is at or above target)").contains("Current price: 201.00");
    }

    @Test
    void unknownPriceIsOmitted() {
        NotificationEmail e = NotificationEmail.of(hit(KanbanColumn.SUPPORT, "185", null), "http://x/alerts");
        assertThat(e.body()).doesNotContain("Current price");
    }

    private static Notification of(NotificationType type, KanbanColumn column) {
        return new Notification(UUID.randomUUID(), type, UUID.randomUUID(), "AAPL", column, new BigDecimal("185"),
                null, "msg", Instant.parse("2026-11-03T10:15:30Z"), "user-1");
    }

    @Test
    void cardCreatedEmail() {
        NotificationEmail e = NotificationEmail.of(of(NotificationType.CARD_CREATED, KanbanColumn.SUPPORT),
                "http://localhost:8000/alerts");
        assertThat(e.subject()).isEqualTo("Tradex: new Support card AAPL @ 185.00");
        assertThat(e.body())
                .contains("You added a Support card for AAPL")
                .contains("Target price:  185.00")
                .contains("Time:          2026-11-03 10:15:30 UTC")
                .contains("http://localhost:8000/alerts")
                .doesNotContain("SUPPORT");
    }

    @Test
    void cardDeletedEmail() {
        NotificationEmail e = NotificationEmail.of(of(NotificationType.CARD_DELETED, KanbanColumn.RESISTANCE),
                "http://localhost:8000/alerts");
        assertThat(e.subject()).isEqualTo("Tradex: Resistance card AAPL @ 185.00 deleted");
        assertThat(e.body()).contains("You deleted the Resistance card for AAPL").contains("no more alerts");
    }
}
