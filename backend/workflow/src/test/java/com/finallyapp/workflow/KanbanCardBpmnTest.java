package com.finallyapp.workflow;

import io.camunda.zeebe.model.bpmn.Bpmn;
import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import io.camunda.zeebe.model.bpmn.instance.ExclusiveGateway;
import io.camunda.zeebe.model.bpmn.instance.FlowNode;
import io.camunda.zeebe.model.bpmn.instance.IntermediateCatchEvent;
import io.camunda.zeebe.model.bpmn.instance.Message;
import io.camunda.zeebe.model.bpmn.instance.MessageEventDefinition;
import io.camunda.zeebe.model.bpmn.instance.Process;
import io.camunda.zeebe.model.bpmn.instance.SequenceFlow;
import io.camunda.zeebe.model.bpmn.instance.ServiceTask;
import io.camunda.zeebe.model.bpmn.instance.StartEvent;
import io.camunda.zeebe.model.bpmn.instance.SubProcess;
import io.camunda.zeebe.model.bpmn.instance.bpmndi.BpmnShape;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeHeader;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeSubscription;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeTaskDefinition;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeTaskHeaders;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Parses + schema-validates camunda/bpmn/kanban-card.bpmn (from the classpath) and checks the contract. */
class KanbanCardBpmnTest {

    private static BpmnModelInstance model;

    @BeforeAll
    static void load() throws Exception {
        try (InputStream in = KanbanCardBpmnTest.class.getResourceAsStream("/bpmn/kanban-card.bpmn")) {
            assertThat(in).as("BPMN on classpath under bpmn/").isNotNull();
            model = Bpmn.readModelFromStream(in);
        }
        Bpmn.validateModel(model);
    }

    @Test
    void processIsExecutableWithContractId() {
        Process process = model.getModelElementById(KanbanProcess.PROCESS_ID);
        assertThat(process).isNotNull();
        assertThat(process.isExecutable()).isTrue();
    }

    @Test
    void startIsFollowedByExclusiveGatewayOnColumn() {
        StartEvent start = model.getModelElementById("StartEvent_CardCreated");
        FlowNode next = start.getOutgoing().iterator().next().getTarget();
        assertThat(next).isInstanceOf(ExclusiveGateway.class);
        ExclusiveGateway gateway = (ExclusiveGateway) next;

        List<String> targets = gateway.getOutgoing().stream().map(f -> f.getTarget().getName()).toList();
        assertThat(targets).containsExactlyInAnyOrder(
                "Notify: card created in Support", "Notify: card created in Resistance");

        SequenceFlow support = model.getModelElementById("Flow_Support");
        assertThat(support.getConditionExpression().getTextContent()).isEqualTo("=column = \"SUPPORT\"");
        assertThat(gateway.getDefault().getId()).isEqualTo("Flow_Resistance");
    }

    private static String type(ServiceTask t) {
        return t.getSingleExtensionElement(ZeebeTaskDefinition.class).getType();
    }

    private static String header(ServiceTask t) {
        ZeebeTaskHeaders headers = t.getSingleExtensionElement(ZeebeTaskHeaders.class);
        return headers.getHeaders().stream()
                .filter(h -> h.getKey().equals(KanbanProcess.HEADER_NOTIFICATION_TYPE))
                .map(ZeebeHeader::getValue).findFirst().orElseThrow();
    }

    @Test
    void everyServiceTaskIsNotifyOrEmailWithTypeHeader() {
        Collection<ServiceTask> tasks = model.getModelElementsByType(ServiceTask.class);
        assertThat(tasks).hasSize(9);
        assertThat(tasks).extracting(KanbanCardBpmnTest::type)
                .containsOnly(KanbanProcess.JOB_TYPE_NOTIFY, KanbanProcess.JOB_TYPE_EMAIL);

        List<String> toasts = tasks.stream().filter(t -> type(t).equals(KanbanProcess.JOB_TYPE_NOTIFY))
                .map(KanbanCardBpmnTest::header).toList();
        assertThat(toasts).containsExactlyInAnyOrder("CARD_CREATED", "CARD_CREATED", "TARGET_HIT", "CARD_MOVED",
                "CARD_DELETED");

        // emails: created (both columns), deleted, target hit - never moved
        List<String> emails = tasks.stream().filter(t -> type(t).equals(KanbanProcess.JOB_TYPE_EMAIL))
                .map(KanbanCardBpmnTest::header).toList();
        assertThat(emails).containsExactlyInAnyOrder("CARD_CREATED", "CARD_CREATED", "CARD_DELETED", "TARGET_HIT");
    }

    /** Asserts start -> toast -> email -> end with the given ids. */
    private static void assertToastThenEmail(String fromId, String toastId, String emailId, String nextId) {
        FlowNode from = model.getModelElementById(fromId);
        FlowNode toast = from.getOutgoing().iterator().next().getTarget();
        assertThat(toast.getId()).isEqualTo(toastId);
        FlowNode email = toast.getOutgoing().iterator().next().getTarget();
        assertThat(email.getId()).isEqualTo(emailId);
        assertThat(type((ServiceTask) email)).isEqualTo(KanbanProcess.JOB_TYPE_EMAIL);
        assertThat(header((ServiceTask) email)).isEqualTo(header((ServiceTask) toast));
        assertThat(email.getOutgoing().iterator().next().getTarget().getId()).isEqualTo(nextId);
    }

    @Test
    void targetHitSendsToastThenEmail() {
        assertToastThenEmail("StartEvent_TargetPriceHit", "Task_NotifyTargetHit", "Task_EmailTargetHit",
                "EndEvent_TargetHitNotified");
    }

    @Test
    void cardCreatedSendsToastThenEmailOnBothGatewayBranches() {
        SequenceFlow support = model.getModelElementById("Flow_Support");
        SequenceFlow resistance = model.getModelElementById("Flow_Resistance");
        assertThat(support.getTarget().getId()).isEqualTo("Task_NotifyCreatedSupport");
        assertThat(resistance.getTarget().getId()).isEqualTo("Task_NotifyCreatedResistance");
        FlowNode supportEmail = support.getTarget().getOutgoing().iterator().next().getTarget();
        FlowNode resistanceEmail = resistance.getTarget().getOutgoing().iterator().next().getTarget();
        for (FlowNode email : List.of(supportEmail, resistanceEmail)) {
            assertThat(type((ServiceTask) email)).isEqualTo(KanbanProcess.JOB_TYPE_EMAIL);
            assertThat(header((ServiceTask) email)).isEqualTo("CARD_CREATED");
            assertThat(email.getOutgoing().iterator().next().getTarget().getId()).isEqualTo("Gateway_Merge");
        }
    }

    @Test
    void cardDeletedSendsToastThenEmailBeforeEnding() {
        assertToastThenEmail("Event_CardDeleted", "Task_NotifyCardDeleted", KanbanProcess.TASK_EMAIL_CARD_DELETED,
                "EndEvent_CardDeleted");
    }

    @Test
    void cardMovedIsNotEmailed() {
        FlowNode toast = model.getModelElementById("Task_NotifyCardMoved");
        assertThat(toast.getOutgoing().iterator().next().getTarget().getId()).isEqualTo("EndEvent_CardMovedNotified");
    }

    @Test
    void messagesUseCardIdCorrelationKey() {
        Collection<Message> messages = model.getModelElementsByType(Message.class);
        assertThat(messages).extracting(Message::getName).containsExactlyInAnyOrder(
                KanbanProcess.MSG_CARD_DELETED, KanbanProcess.MSG_TARGET_PRICE_HIT, KanbanProcess.MSG_CARD_MOVED);
        for (Message m : messages) {
            ZeebeSubscription sub = m.getSingleExtensionElement(ZeebeSubscription.class);
            assertThat(sub.getCorrelationKey()).isEqualTo("=cardId");
        }
    }

    @Test
    void mainFlowWaitsForCardDeleted() {
        IntermediateCatchEvent wait = model.getModelElementById("Event_CardDeleted");
        MessageEventDefinition def = (MessageEventDefinition) wait.getEventDefinitions().iterator().next();
        assertThat(def.getMessage().getName()).isEqualTo(KanbanProcess.MSG_CARD_DELETED);
    }

    @Test
    void targetHitAndMoveAreNonInterruptingEventSubprocesses() {
        for (String id : List.of("EventSub_TargetPriceHit", "EventSub_CardMoved")) {
            SubProcess sub = model.getModelElementById(id);
            assertThat(sub.triggeredByEvent()).isTrue();
            StartEvent start = sub.getChildElementsByType(StartEvent.class).iterator().next();
            assertThat(start.isInterrupting()).isFalse();
        }
    }

    @Test
    void everyFlowNodeHasDiagramShape() {
        Set<String> shaped = model.getModelElementsByType(BpmnShape.class).stream()
                .map(s -> s.getBpmnElement().getId()).collect(Collectors.toSet());
        Set<String> nodes = model.getModelElementsByType(FlowNode.class).stream()
                .map(FlowNode::getId).collect(Collectors.toSet());
        assertThat(shaped).containsAll(nodes);
    }
}
