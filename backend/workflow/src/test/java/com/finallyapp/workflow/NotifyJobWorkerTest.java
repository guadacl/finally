package com.finallyapp.workflow;

import com.finallyapp.contracts.KanbanColumn;
import com.finallyapp.contracts.Notification;
import com.finallyapp.contracts.NotificationPublisher;
import com.finallyapp.contracts.NotificationType;
import io.camunda.client.api.response.ActivatedJob;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NotifyJobWorkerTest {

    private final UUID cardId = UUID.randomUUID();

    private ActivatedJob job(long key, String type, Map<String, Object> extra) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("cardId", cardId.toString());
        vars.put("ticker", "AAPL");
        vars.put("column", "SUPPORT");
        vars.put("targetPrice", 185); // Zeebe hands numbers back as Integer/Double
        vars.putAll(extra);
        ActivatedJob job = mock(ActivatedJob.class);
        when(job.getKey()).thenReturn(key);
        when(job.getVariablesAsMap()).thenReturn(vars);
        when(job.getCustomHeaders()).thenReturn(type == null ? Map.of() : Map.of("notificationType", type));
        return job;
    }

    @Test
    void buildsTargetHitNotification() {
        Notification n = NotifyJobWorker.toNotification(job(42, "TARGET_HIT", Map.of("price", 184.97)));
        assertThat(n.type()).isEqualTo(NotificationType.TARGET_HIT);
        assertThat(n.cardId()).isEqualTo(cardId);
        assertThat(n.column()).isEqualTo(KanbanColumn.SUPPORT);
        assertThat(n.targetPrice()).isEqualByComparingTo("185");
        assertThat(n.price()).isEqualByComparingTo("184.97");
        assertThat(n.message()).isEqualTo("AAPL hit support 185.00 (now 184.97)");
    }

    @Test
    void createdAndMovedHaveNoPrice() {
        Notification created = NotifyJobWorker.toNotification(job(1, "CARD_CREATED", Map.of("price", 1.0)));
        assertThat(created.price()).isNull();
        assertThat(created.message()).isEqualTo("New SUPPORT card: AAPL @ 185.00");

        Notification moved = NotifyJobWorker.toNotification(
                job(2, "CARD_MOVED", Map.of("column", "RESISTANCE", "targetPrice", 200.0)));
        assertThat(moved.message()).isEqualTo("AAPL moved to RESISTANCE @ 200.00");
    }

    @Test
    void typeMayComeFromVariableWhenHeaderMissing() {
        Notification n = NotifyJobWorker.toNotification(job(3, null, Map.of("notificationType", "CARD_CREATED")));
        assertThat(n.type()).isEqualTo(NotificationType.CARD_CREATED);
    }

    @Test
    void retriedJobGetsSameNotificationId() {
        Notification a = NotifyJobWorker.toNotification(job(77, "CARD_CREATED", Map.of()));
        Notification b = NotifyJobWorker.toNotification(job(77, "CARD_CREATED", Map.of()));
        assertThat(a.id()).isEqualTo(b.id());
    }

    @Test
    void handlePublishes() {
        List<Notification> published = new CopyOnWriteArrayList<>();
        new NotifyJobWorker(published::add).handle(job(5, "CARD_CREATED", Map.of()));
        assertThat(published).hasSize(1);
    }

    @Test
    void handleNeverFailsTheJob() {
        NotificationPublisher broken = mock(NotificationPublisher.class);
        doThrow(new RuntimeException("no subscribers")).when(broken).publish(any());
        NotifyJobWorker worker = new NotifyJobWorker(broken);

        assertThatNoException().isThrownBy(() -> worker.handle(job(6, "CARD_CREATED", Map.of())));
        assertThatNoException().isThrownBy(() -> worker.handle(job(7, null, Map.of()))); // malformed
        assertThatNoException().isThrownBy(() -> worker.handle(job(8, "BOGUS", Map.of())));
    }
}
