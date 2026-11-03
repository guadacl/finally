package com.finallyapp.workflow;

import com.finallyapp.contracts.KanbanCardRef;
import com.finallyapp.contracts.KanbanColumn;
import com.finallyapp.contracts.Notification;
import com.finallyapp.contracts.NotificationPublisher;
import com.finallyapp.contracts.NotificationType;
import io.camunda.client.CamundaClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Fallback behaviour of {@link CamundaKanbanWorkflow} and behaviour of {@link DirectKanbanWorkflow}. */
class KanbanWorkflowFallbackTest {

    private final List<Notification> published = new CopyOnWriteArrayList<>();
    private final NotificationPublisher publisher = published::add;
    private final KanbanCardRef card =
            new KanbanCardRef(UUID.randomUUID(), "AAPL", KanbanColumn.SUPPORT, new BigDecimal("185"), "user-1");

    @SuppressWarnings("unchecked")
    private CamundaKanbanWorkflow workflowWith(CamundaClient client) {
        ObjectProvider<CamundaClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);
        return new CamundaKanbanWorkflow(provider, publisher);
    }

    @Test
    void cardCreatedFallsBackWhenClientThrows() {
        CamundaClient client = mock(CamundaClient.class);
        when(client.newCreateInstanceCommand()).thenThrow(new RuntimeException("UNAVAILABLE"));

        Long key = workflowWith(client).cardCreated(card);

        assertThat(key).isNull();
        assertThat(published).singleElement().satisfies(n -> {
            assertThat(n.type()).isEqualTo(NotificationType.CARD_CREATED);
            assertThat(n.message()).isEqualTo("New Support card: AAPL @ 185.00");
        });
    }

    @Test
    void messagesFallBackWhenClientThrows() {
        CamundaClient client = mock(CamundaClient.class);
        when(client.newCorrelateMessageCommand()).thenThrow(new RuntimeException("UNAVAILABLE"));
        CamundaKanbanWorkflow workflow = workflowWith(client);

        workflow.targetPriceHit(card, new BigDecimal("184.97"));
        workflow.cardMoved(new KanbanCardRef(card.id(), "AAPL", KanbanColumn.RESISTANCE, new BigDecimal("200"), "user-1"));
        workflow.cardDeleted(card.id());

        assertThat(published).extracting(Notification::message).containsExactly(
                "AAPL hit support 185.00 (now 184.97)", "AAPL moved to Resistance @ 200.00");
    }

    @Test
    void noClientBeanFallsBack() {
        CamundaKanbanWorkflow workflow = workflowWith(null);
        assertThat(workflow.cardCreated(card)).isNull();
        assertThat(published).hasSize(1);
    }

    @Test
    void fallbackNeverThrowsEvenIfPublisherFails() {
        CamundaClient client = mock(CamundaClient.class);
        when(client.newCreateInstanceCommand()).thenThrow(new RuntimeException("down"));
        when(client.newCorrelateMessageCommand()).thenThrow(new RuntimeException("down"));
        NotificationPublisher broken = mock(NotificationPublisher.class);
        doThrow(new RuntimeException("sse broken")).when(broken).publish(org.mockito.ArgumentMatchers.any());
        @SuppressWarnings("unchecked")
        ObjectProvider<CamundaClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);
        CamundaKanbanWorkflow workflow = new CamundaKanbanWorkflow(provider, broken);

        assertThatNoException().isThrownBy(() -> {
            workflow.cardCreated(card);
            workflow.cardMoved(card);
            workflow.targetPriceHit(card, BigDecimal.ONE);
            workflow.cardDeleted(card.id());
        });
    }

    @Test
    void directWorkflowPublishesDirectly() {
        DirectKanbanWorkflow direct = new DirectKanbanWorkflow(publisher);

        assertThat(direct.cardCreated(card)).isNull();
        direct.cardMoved(card);
        direct.targetPriceHit(card, new BigDecimal("184.97"));
        direct.cardDeleted(card.id());

        assertThat(published).extracting(Notification::type).containsExactly(
                NotificationType.CARD_CREATED, NotificationType.CARD_MOVED, NotificationType.TARGET_HIT);
        assertThat(published).allSatisfy(n -> {
            assertThat(n.cardId()).isEqualTo(card.id());
            assertThat(n.ticker()).isEqualTo("AAPL");
        });
    }
}
