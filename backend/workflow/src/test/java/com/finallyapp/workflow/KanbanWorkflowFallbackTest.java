package com.finallyapp.workflow;

import com.finallyapp.contracts.KanbanCardRef;
import com.finallyapp.contracts.KanbanColumn;
import com.finallyapp.contracts.Notification;
import com.finallyapp.contracts.NotificationEmailer;
import com.finallyapp.contracts.NotificationPublisher;
import com.finallyapp.contracts.NotificationSms;
import com.finallyapp.contracts.NotificationType;
import io.camunda.client.CamundaClient;
import io.camunda.client.api.CamundaFuture;
import io.camunda.client.api.command.CancelProcessInstanceCommandStep1;
import io.camunda.client.api.fetch.ProcessDefinitionGetXmlRequest;
import io.camunda.client.api.fetch.ProcessInstanceGetRequest;
import io.camunda.client.api.response.CorrelateMessageResponse;
import io.camunda.client.api.search.response.ProcessInstance;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Fallback behaviour of {@link CamundaKanbanWorkflow} and behaviour of {@link DirectKanbanWorkflow}. */
class KanbanWorkflowFallbackTest {

    private final List<Notification> published = new CopyOnWriteArrayList<>();
    private final List<Notification> emailed = new CopyOnWriteArrayList<>();
    private final NotificationPublisher publisher = published::add;
    private final NotificationEmailer emailer = emailed::add;
    private final List<Notification> texted = new CopyOnWriteArrayList<>();
    private final NotificationSms sms = texted::add;
    private final KanbanCardRef card =
            new KanbanCardRef(UUID.randomUUID(), "AAPL", KanbanColumn.SUPPORT, new BigDecimal("185"), "user-1");

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private CamundaKanbanWorkflow workflowWith(CamundaClient client) {
        return new CamundaKanbanWorkflow(provider(client), publisher);
    }

    /** With an emailer and an SMS sender; background work runs inline. */
    private CamundaKanbanWorkflow emailingWorkflow(CamundaClient client) {
        return new CamundaKanbanWorkflow(provider(client),
                new DirectKanbanWorkflow(publisher, provider(emailer), provider(sms)), Runnable::run);
    }

    private static CamundaClient unreachable() {
        CamundaClient client = mock(CamundaClient.class);
        when(client.newCorrelateMessageCommand()).thenThrow(new RuntimeException("UNAVAILABLE"));
        when(client.newCreateInstanceCommand()).thenThrow(new RuntimeException("UNAVAILABLE"));
        return client;
    }

    @Test
    void cardCreatedFallsBackWhenClientThrows() {
        Long key = workflowWith(unreachable()).cardCreated(card);

        assertThat(key).isNull();
        assertThat(published).singleElement().satisfies(n -> {
            assertThat(n.type()).isEqualTo(NotificationType.CARD_CREATED);
            assertThat(n.message()).isEqualTo("New Support card: AAPL @ 185.00");
        });
    }

    @Test
    void messagesFallBackWhenClientThrows() {
        CamundaKanbanWorkflow workflow = workflowWith(unreachable());

        workflow.targetPriceHit(card, new BigDecimal("184.97"));
        workflow.cardMoved(new KanbanCardRef(card.id(), "AAPL", KanbanColumn.RESISTANCE, new BigDecimal("200"), "user-1"));
        workflow.cardDeleted(card, null);

        assertThat(published).extracting(Notification::message).containsExactly(
                "AAPL hit support 185.00 (now 184.97)", "AAPL moved to Resistance @ 200.00",
                "Support card deleted: AAPL @ 185.00");
    }

    @Test
    void noClientBeanFallsBack() {
        CamundaKanbanWorkflow workflow = workflowWith(null);
        assertThat(workflow.cardCreated(card)).isNull();
        assertThat(published).hasSize(1);
    }

    @Test
    void fallbackNeverThrowsEvenIfPublisherFails() {
        NotificationPublisher broken = mock(NotificationPublisher.class);
        doThrow(new RuntimeException("sse broken")).when(broken).publish(any());
        CamundaKanbanWorkflow workflow = new CamundaKanbanWorkflow(provider(unreachable()), broken);

        assertThatNoException().isThrownBy(() -> {
            workflow.cardCreated(card);
            workflow.cardMoved(card);
            workflow.targetPriceHit(card, BigDecimal.ONE);
            workflow.cardDeleted(card, 42L);
        });
    }

    @Test
    void directWorkflowPublishesDirectly() {
        DirectKanbanWorkflow direct = new DirectKanbanWorkflow(publisher);

        assertThat(direct.cardCreated(card)).isNull();
        direct.cardMoved(card);
        direct.targetPriceHit(card, new BigDecimal("184.97"));
        direct.cardDeleted(card, null);

        assertThat(published).extracting(Notification::type).containsExactly(
                NotificationType.CARD_CREATED, NotificationType.CARD_MOVED, NotificationType.TARGET_HIT,
                NotificationType.CARD_DELETED);
        assertThat(published).allSatisfy(n -> {
            assertThat(n.cardId()).isEqualTo(card.id());
            assertThat(n.ticker()).isEqualTo("AAPL");
            assertThat(n.userId()).isEqualTo("user-1");
        });
    }

    @Test
    void fallbackEmailsCreatedDeletedAndTargetHitOnceButNotMoved() {
        CamundaKanbanWorkflow workflow = emailingWorkflow(unreachable());

        workflow.cardCreated(card);
        workflow.cardMoved(card);
        workflow.targetPriceHit(card, new BigDecimal("184.97"));
        workflow.cardDeleted(card, null);

        assertThat(emailed).extracting(Notification::type).containsExactly(
                NotificationType.CARD_CREATED, NotificationType.TARGET_HIT, NotificationType.CARD_DELETED);
        // each email and SMS is the same notification as its toast
        assertThat(published).filteredOn(n -> n.type() != NotificationType.CARD_MOVED).containsExactlyElementsOf(emailed);
        assertThat(texted).containsExactlyElementsOf(emailed);
    }

    @Test
    void smsFailureDoesNotStopEmailOrToast() {
        NotificationSms broken = mock(NotificationSms.class);
        doThrow(new IllegalStateException("mcp down")).when(broken).send(any());
        DirectKanbanWorkflow direct = new DirectKanbanWorkflow(publisher, provider(emailer), provider(broken));

        assertThatNoException().isThrownBy(() -> direct.cardCreated(card));
        verify(broken).send(any());
        assertThat(published).hasSize(1);
        assertThat(emailed).hasSize(1);
    }

    @Test
    void directWorkflowSurvivesEmailerFailure() {
        NotificationEmailer broken = mock(NotificationEmailer.class);
        doThrow(new IllegalStateException("mcp down")).when(broken).send(any());
        DirectKanbanWorkflow direct = new DirectKanbanWorkflow(publisher, provider(broken));

        assertThatNoException().isThrownBy(() -> direct.targetPriceHit(card, new BigDecimal("184.97")));
        verify(broken).send(any());
        assertThat(published).hasSize(1);

        // no emailer bean: toast only
        DirectKanbanWorkflow noMail = new DirectKanbanWorkflow(publisher, provider((NotificationEmailer) null));
        assertThatNoException().isThrownBy(() -> noMail.cardDeleted(card, null));
        assertThat(published).hasSize(2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void uncorrelatableDeleteNotifiesDirectlyAndCancelsTheInstance() {
        CamundaClient client = mock(CamundaClient.class);
        when(client.newCorrelateMessageCommand()).thenThrow(new RuntimeException("NOT_FOUND"));
        CancelProcessInstanceCommandStep1 cancel = mock(CancelProcessInstanceCommandStep1.class, RETURNS_SELF);
        when(cancel.send()).thenReturn(mock(CamundaFuture.class));
        when(client.newCancelInstanceCommand(anyLong())).thenReturn(cancel);

        emailingWorkflow(client).cardDeleted(card, 4711L);

        assertThat(published).extracting(Notification::type).containsExactly(NotificationType.CARD_DELETED);
        assertThat(emailed).extracting(Notification::type).containsExactly(NotificationType.CARD_DELETED);
        assertThat(texted).extracting(Notification::type).containsExactly(NotificationType.CARD_DELETED);
        verify(client).newCancelInstanceCommand(4711L);
    }

    @SuppressWarnings("unchecked")
    private static CamundaClient clientWithDefinition(String bpmnXml) {
        CamundaClient client = mock(CamundaClient.class);
        CorrelateMessageResponse response = mock(CorrelateMessageResponse.class);
        when(response.getProcessInstanceKey()).thenReturn(99L);

        ProcessInstance instance = mock(ProcessInstance.class);
        when(instance.getProcessDefinitionKey()).thenReturn(7L);
        CamundaFuture<ProcessInstance> instanceFuture = mock(CamundaFuture.class);
        when(instanceFuture.join(anyLong(), any())).thenReturn(instance);
        ProcessInstanceGetRequest get = mock(ProcessInstanceGetRequest.class);
        when(get.send()).thenReturn(instanceFuture);
        when(client.newProcessInstanceGetRequest(99L)).thenReturn(get);

        CamundaFuture<String> xmlFuture = mock(CamundaFuture.class);
        when(xmlFuture.join(anyLong(), any())).thenReturn(bpmnXml);
        ProcessDefinitionGetXmlRequest xml = mock(ProcessDefinitionGetXmlRequest.class);
        when(xml.send()).thenReturn(xmlFuture);
        when(client.newProcessDefinitionGetXmlRequest(7L)).thenReturn(xml);
        return client;
    }

    @Test
    void deleteOfAnOldProcessVersionNotifiesDirectly() {
        CamundaClient client = clientWithDefinition("<bpmn>no deletion email here</bpmn>");
        CamundaKanbanWorkflow workflow = emailingWorkflow(client);
        CorrelateMessageResponse response = mock(CorrelateMessageResponse.class);
        when(response.getProcessInstanceKey()).thenReturn(99L);

        workflow.notifyIfLegacyVersion(card, response);
        workflow.notifyIfLegacyVersion(card, response); // definition XML is cached

        assertThat(emailed).extracting(Notification::type)
                .containsExactly(NotificationType.CARD_DELETED, NotificationType.CARD_DELETED);
        assertThat(texted).hasSize(2);
        assertThat(published).hasSize(2);
        verify(client, org.mockito.Mockito.times(1)).newProcessDefinitionGetXmlRequest(7L);
    }

    @Test
    void deleteOfAnEmailOnlyProcessVersionSendsJustTheSms() {
        CamundaClient client = clientWithDefinition("<bpmn id=\"" + KanbanProcess.TASK_EMAIL_CARD_DELETED + "\"/>");
        CamundaKanbanWorkflow workflow = emailingWorkflow(client);
        CorrelateMessageResponse response = mock(CorrelateMessageResponse.class);
        when(response.getProcessInstanceKey()).thenReturn(99L);

        workflow.notifyIfLegacyVersion(card, response);

        // the old version already toasted and emailed
        assertThat(published).isEmpty();
        assertThat(emailed).isEmpty();
        assertThat(texted).singleElement().satisfies(n -> {
            assertThat(n.type()).isEqualTo(NotificationType.CARD_DELETED);
            assertThat(n.userId()).isEqualTo("user-1");
        });
    }

    @Test
    void deleteOfTheCurrentProcessVersionLeavesEverythingToCamunda() {
        CamundaClient client = clientWithDefinition("<bpmn id=\"" + KanbanProcess.TASK_EMAIL_CARD_DELETED + "\" x=\""
                + KanbanProcess.TASK_SMS_CARD_DELETED + "\"/>");
        CamundaKanbanWorkflow workflow = emailingWorkflow(client);
        CorrelateMessageResponse response = mock(CorrelateMessageResponse.class);
        when(response.getProcessInstanceKey()).thenReturn(99L);

        workflow.notifyIfLegacyVersion(card, response);

        assertThat(published).isEmpty();
        assertThat(emailed).isEmpty();
        assertThat(texted).isEmpty();
    }

    @Test
    void failedVersionLookupSendsNothing() {
        CamundaClient client = mock(CamundaClient.class);
        when(client.newProcessInstanceGetRequest(anyLong())).thenThrow(new RuntimeException("404"));
        CamundaKanbanWorkflow workflow = emailingWorkflow(client);
        CorrelateMessageResponse response = mock(CorrelateMessageResponse.class);
        when(response.getProcessInstanceKey()).thenReturn(99L);

        assertThatNoException().isThrownBy(() -> workflow.notifyIfLegacyVersion(card, response));
        assertThat(emailed).isEmpty();
        verify(client, never()).newCancelInstanceCommand(anyLong());
    }
}
