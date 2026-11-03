package com.finallyapp.workflow;

import com.finallyapp.contracts.KanbanCardRef;
import com.finallyapp.contracts.KanbanWorkflow;
import com.finallyapp.contracts.Notification;
import com.finallyapp.contracts.NotificationEmailer;
import com.finallyapp.contracts.NotificationPublisher;
import com.finallyapp.contracts.NotificationType;
import java.math.BigDecimal;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * {@link KanbanWorkflow} without Camunda: publishes the notifications directly and emails CARD_CREATED,
 * CARD_DELETED and TARGET_HIT (what the process's {@code email-notify} tasks would do).
 * Active when {@code finally.workflow.enabled=false} (e.g. backend tests). Also used internally by
 * {@link CamundaKanbanWorkflow} as its fallback, so each event is emailed exactly once on either path.
 */
@Component
@ConditionalOnProperty(name = "finally.workflow.enabled", havingValue = "false")
public class DirectKanbanWorkflow implements KanbanWorkflow {

    private static final Logger log = LoggerFactory.getLogger(DirectKanbanWorkflow.class);

    private final NotificationPublisher publisher;
    /** Supplies null when there is no emailer (e.g. the workflow module on its own). */
    private final Supplier<NotificationEmailer> emailer;

    public DirectKanbanWorkflow(NotificationPublisher publisher) {
        this(publisher, () -> null);
    }

    @Autowired
    public DirectKanbanWorkflow(NotificationPublisher publisher, ObjectProvider<NotificationEmailer> emailer) {
        this(publisher, (Supplier<NotificationEmailer>) emailer::getIfAvailable);
    }

    DirectKanbanWorkflow(NotificationPublisher publisher, Supplier<NotificationEmailer> emailer) {
        this.publisher = publisher;
        this.emailer = emailer;
    }

    @Override
    public Long cardCreated(KanbanCardRef card) {
        email(publish(NotificationType.CARD_CREATED, card, null));
        return null;
    }

    @Override
    public void cardMoved(KanbanCardRef card) {
        publish(NotificationType.CARD_MOVED, card, null); // toast only, never emailed
    }

    @Override
    public void targetPriceHit(KanbanCardRef card, BigDecimal price) {
        email(publish(NotificationType.TARGET_HIT, card, price));
    }

    @Override
    public void cardDeleted(KanbanCardRef card, Long processInstanceKey) {
        email(publish(NotificationType.CARD_DELETED, card, null));
    }

    /** Publishes the toast; returns the notification (also when publishing failed), null if it could not be built. */
    Notification publish(NotificationType type, KanbanCardRef card, BigDecimal price) {
        Notification n;
        try {
            n = NotificationMessages.build(type, card, price);
        } catch (Exception e) {
            log.warn("Could not build {} notification for card {}: {}", type, card == null ? null : card.id(),
                    e.toString());
            return null;
        }
        try {
            publisher.publish(n);
        } catch (Exception e) {
            log.warn("Could not publish {} notification for card {}: {}", type, card.id(), e.toString());
        }
        return n;
    }

    /** Hands the notification to the emailer (non-blocking, never throws). */
    void email(Notification n) {
        if (n == null || !NotificationEmailer.isEmailed(n.type())) {
            return;
        }
        try {
            NotificationEmailer e = emailer.get();
            if (e != null) {
                e.send(n);
            }
        } catch (Exception e) {
            log.warn("Could not send {} email for card {}: {}", n.type(), n.cardId(), e.toString());
        }
    }
}
