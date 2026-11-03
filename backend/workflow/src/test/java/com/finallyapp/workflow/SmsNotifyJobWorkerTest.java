package com.finallyapp.workflow;

import com.finallyapp.contracts.Notification;
import com.finallyapp.contracts.NotificationType;
import com.finallyapp.contracts.NotificationSms;
import io.camunda.client.api.response.ActivatedJob;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SmsNotifyJobWorkerTest {

    private final NotificationSms sms = mock(NotificationSms.class);

    @SuppressWarnings("unchecked")
    private SmsNotifyJobWorker worker(NotificationSms e) {
        ObjectProvider<NotificationSms> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(e);
        return new SmsNotifyJobWorker(provider);
    }

    private static ActivatedJob job(long key, String type) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("cardId", UUID.randomUUID().toString());
        vars.put("ticker", "AAPL");
        vars.put("column", "RESISTANCE");
        vars.put("targetPrice", 200);
        vars.put("price", 200.25);
        vars.put("userId", "kc-sub-1");
        ActivatedJob job = mock(ActivatedJob.class);
        when(job.getKey()).thenReturn(key);
        when(job.getVariablesAsMap()).thenReturn(vars);
        when(job.getCustomHeaders()).thenReturn(type == null ? Map.of() : Map.of("notificationType", type));
        return job;
    }

    @Test
    void handsTargetHitToSmsSenderWithStableIdPerChannel() {
        worker(sms).handle(job(77, "TARGET_HIT"));
        worker(sms).handle(job(77, "TARGET_HIT")); // re-delivered job

        ArgumentCaptor<Notification> sent = ArgumentCaptor.forClass(Notification.class);
        verify(sms, org.mockito.Mockito.times(2)).send(sent.capture());
        Notification n = sent.getAllValues().get(0);
        assertThat(n.type()).isEqualTo(NotificationType.TARGET_HIT);
        assertThat(n.userId()).isEqualTo("kc-sub-1");
        assertThat(n.price()).isEqualByComparingTo("200.25");
        assertThat(n.id()).isEqualTo(sent.getAllValues().get(1).id());
        // channel-specific ids: different from the toast's and the email's id for a job with the same key
        assertThat(n.id()).isNotEqualTo(NotifyJobWorker.toNotification(job(77, "TARGET_HIT")).id());
        assertThat(n.id()).isNotEqualTo(NotifyJobWorker.toNotification(job(77, "TARGET_HIT"), "email-notify:").id());
    }

    @Test
    void movedAndMalformedJobsAreNotTexted() {
        worker(sms).handle(job(1, "CARD_MOVED"));
        worker(sms).handle(job(2, null)); // no type -> malformed, completed anyway
        verify(sms, never()).send(any());
    }

    @Test
    void createdAndDeletedAreTextedWithoutPrice() {
        worker(sms).handle(job(5, "CARD_CREATED"));
        worker(sms).handle(job(6, "CARD_DELETED"));
        ArgumentCaptor<Notification> sent = ArgumentCaptor.forClass(Notification.class);
        verify(sms, org.mockito.Mockito.times(2)).send(sent.capture());
        assertThat(sent.getAllValues()).extracting(Notification::type)
                .containsExactly(NotificationType.CARD_CREATED, NotificationType.CARD_DELETED);
        assertThat(sent.getAllValues()).allSatisfy(n -> {
            assertThat(n.price()).isNull();
            assertThat(n.userId()).isEqualTo("kc-sub-1");
            assertThat(n.column()).isEqualTo(com.finallyapp.contracts.KanbanColumn.RESISTANCE);
        });
        assertThat(sent.getAllValues().get(1).message()).isEqualTo("Resistance card deleted: AAPL @ 200.00");
    }

    @Test
    void neverFailsTheJob() {
        doThrow(new IllegalStateException("mcp down")).when(sms).send(any());
        assertThatNoException().isThrownBy(() -> worker(sms).handle(job(3, "TARGET_HIT")));
        assertThatNoException().isThrownBy(() -> worker(null).handle(job(4, "TARGET_HIT")));
    }
}
