package com.finallyapp.workflow;

import com.finallyapp.contracts.KanbanCardRef;
import com.finallyapp.contracts.KanbanColumn;
import com.finallyapp.contracts.Notification;
import com.finallyapp.contracts.NotificationType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationMessagesTest {

    @Test
    void cardCreated() {
        assertThat(NotificationMessages.format(NotificationType.CARD_CREATED, "AAPL", KanbanColumn.SUPPORT,
                new BigDecimal("185"), null)).isEqualTo("New SUPPORT card: AAPL @ 185.00");
    }

    @Test
    void cardMoved() {
        assertThat(NotificationMessages.format(NotificationType.CARD_MOVED, "AAPL", KanbanColumn.RESISTANCE,
                new BigDecimal("200.0"), null)).isEqualTo("AAPL moved to RESISTANCE @ 200.00");
    }

    @Test
    void targetHit() {
        assertThat(NotificationMessages.format(NotificationType.TARGET_HIT, "AAPL", KanbanColumn.SUPPORT,
                new BigDecimal("185.0000"), new BigDecimal("184.9685")))
                .isEqualTo("AAPL hit support 185.00 (now 184.97)");
        assertThat(NotificationMessages.format(NotificationType.TARGET_HIT, "TSLA", KanbanColumn.RESISTANCE,
                new BigDecimal("250"), null)).isEqualTo("TSLA hit resistance 250.00");
    }

    @Test
    void toleratesMissingValues() {
        assertThat(NotificationMessages.format(NotificationType.CARD_CREATED, null, null, null, null))
                .isEqualTo("New ? card: ? @ ?");
    }

    @Test
    void buildFillsAllFields() {
        KanbanCardRef card = new KanbanCardRef(UUID.randomUUID(), "MSFT", KanbanColumn.SUPPORT, new BigDecimal("400"));
        Notification n = NotificationMessages.build(NotificationType.TARGET_HIT, card, new BigDecimal("399.5"));
        assertThat(n.id()).isNotNull();
        assertThat(n.cardId()).isEqualTo(card.id());
        assertThat(n.ticker()).isEqualTo("MSFT");
        assertThat(n.column()).isEqualTo(KanbanColumn.SUPPORT);
        assertThat(n.price()).isEqualByComparingTo("399.5");
        assertThat(n.message()).isEqualTo("MSFT hit support 400.00 (now 399.50)");
        assertThat(n.createdAt()).isNotNull();
    }
}
