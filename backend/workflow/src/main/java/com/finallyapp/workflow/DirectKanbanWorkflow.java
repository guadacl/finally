package com.finallyapp.workflow;

import com.finallyapp.contracts.KanbanCardRef;
import com.finallyapp.contracts.KanbanWorkflow;
import com.finallyapp.contracts.NotificationPublisher;
import com.finallyapp.contracts.NotificationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * {@link KanbanWorkflow} without Camunda: publishes the notifications directly.
 * Active when {@code finally.workflow.enabled=false} (e.g. backend tests). Also used internally by
 * {@link CamundaKanbanWorkflow} as its fallback.
 */
@Component
@ConditionalOnProperty(name = "finally.workflow.enabled", havingValue = "false")
public class DirectKanbanWorkflow implements KanbanWorkflow {

    private static final Logger log = LoggerFactory.getLogger(DirectKanbanWorkflow.class);

    private final NotificationPublisher publisher;

    public DirectKanbanWorkflow(NotificationPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public Long cardCreated(KanbanCardRef card) {
        publish(NotificationType.CARD_CREATED, card, null);
        return null;
    }

    @Override
    public void cardMoved(KanbanCardRef card) {
        publish(NotificationType.CARD_MOVED, card, null);
    }

    @Override
    public void targetPriceHit(KanbanCardRef card, BigDecimal price) {
        publish(NotificationType.TARGET_HIT, card, price);
    }

    @Override
    public void cardDeleted(UUID cardId) {
        // nothing to notify
    }

    void publish(NotificationType type, KanbanCardRef card, BigDecimal price) {
        try {
            publisher.publish(NotificationMessages.build(type, card, price));
        } catch (Exception e) {
            log.warn("Could not publish {} notification for card {}: {}", type, card.id(), e.toString());
        }
    }
}
