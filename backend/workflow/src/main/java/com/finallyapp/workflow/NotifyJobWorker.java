package com.finallyapp.workflow;

import com.finallyapp.contracts.KanbanColumn;
import com.finallyapp.contracts.Notification;
import com.finallyapp.contracts.NotificationPublisher;
import com.finallyapp.contracts.NotificationType;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Handles every {@code finally-notify} service task of the {@code kanban-card} process: builds a
 * {@link Notification} from the job variables and pushes it via {@link NotificationPublisher}.
 *
 * <p>Retry-safe: the notification id is derived from the job key, so a re-delivered job yields the same
 * id (clients can de-duplicate). Publisher errors are logged and the job is still completed, so a broken
 * browser connection never creates incidents.
 */
@Component
@ConditionalOnProperty(name = "finally.workflow.enabled", havingValue = "true", matchIfMissing = true)
public class NotifyJobWorker {

    private static final Logger log = LoggerFactory.getLogger(NotifyJobWorker.class);

    private final NotificationPublisher publisher;

    public NotifyJobWorker(NotificationPublisher publisher) {
        this.publisher = publisher;
    }

    @JobWorker(type = KanbanProcess.JOB_TYPE_NOTIFY, fetchAllVariables = true, autoComplete = true)
    public void handle(ActivatedJob job) {
        Notification notification;
        try {
            notification = toNotification(job);
        } catch (Exception e) {
            log.warn("Ignoring malformed finally-notify job {}: {}", job.getKey(), e.toString());
            return;
        }
        try {
            publisher.publish(notification);
        } catch (Exception e) {
            log.warn("Publishing notification for job {} failed: {}", job.getKey(), e.toString());
        }
    }

    static Notification toNotification(ActivatedJob job) {
        Map<String, Object> vars = job.getVariablesAsMap();
        Map<String, String> headers = job.getCustomHeaders();
        String typeName = headers == null ? null : headers.get(KanbanProcess.HEADER_NOTIFICATION_TYPE);
        if (typeName == null) {
            Object v = vars.get(KanbanProcess.HEADER_NOTIFICATION_TYPE);
            typeName = v == null ? null : v.toString();
        }
        if (typeName == null) {
            throw new IllegalArgumentException("missing notificationType header/variable");
        }
        NotificationType type = NotificationType.valueOf(typeName.trim());

        UUID cardId = toUuid(vars.get(KanbanProcess.VAR_CARD_ID));
        String ticker = vars.get(KanbanProcess.VAR_TICKER) == null ? null : vars.get(KanbanProcess.VAR_TICKER).toString();
        KanbanColumn column = toColumn(vars.get(KanbanProcess.VAR_COLUMN));
        BigDecimal targetPrice = toDecimal(vars.get(KanbanProcess.VAR_TARGET_PRICE));
        BigDecimal price = type == NotificationType.TARGET_HIT ? toDecimal(vars.get(KanbanProcess.VAR_PRICE)) : null;

        UUID id = UUID.nameUUIDFromBytes(("finally-notify:" + job.getKey()).getBytes(StandardCharsets.UTF_8));
        String message = NotificationMessages.format(type, ticker, column, targetPrice, price);
        return new Notification(id, type, cardId, ticker, column, targetPrice, price, message, Instant.now());
    }

    static BigDecimal toDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        try {
            return new BigDecimal(value.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static UUID toUuid(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static KanbanColumn toColumn(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return KanbanColumn.valueOf(value.toString().trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
