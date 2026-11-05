package com.finallyapp.workflow;

import io.camunda.zeebe.model.bpmn.Bpmn;
import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import io.camunda.zeebe.model.bpmn.instance.ExclusiveGateway;
import io.camunda.zeebe.model.bpmn.instance.FlowNode;
import io.camunda.zeebe.model.bpmn.instance.IntermediateCatchEvent;
import io.camunda.zeebe.model.bpmn.instance.Message;
import io.camunda.zeebe.model.bpmn.instance.MessageEventDefinition;
import io.camunda.zeebe.model.bpmn.instance.ParallelGateway;
import io.camunda.zeebe.model.bpmn.instance.Process;
import io.camunda.zeebe.model.bpmn.instance.ScriptTask;
import io.camunda.zeebe.model.bpmn.instance.SequenceFlow;
import io.camunda.zeebe.model.bpmn.instance.ServiceTask;
import io.camunda.zeebe.model.bpmn.instance.StartEvent;
import io.camunda.zeebe.model.bpmn.instance.SubProcess;
import io.camunda.zeebe.model.bpmn.instance.bpmndi.BpmnShape;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeHeader;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeIoMapping;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeOutput;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeScript;
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
    void everyServiceTaskIsNotifyEmailOrSmsWithTypeHeader() {
        Collection<ServiceTask> tasks = model.getModelElementsByType(ServiceTask.class);
        assertThat(tasks).hasSize(13);
        assertThat(tasks).extracting(KanbanCardBpmnTest::type)
                .containsOnly(KanbanProcess.JOB_TYPE_NOTIFY, KanbanProcess.JOB_TYPE_EMAIL, KanbanProcess.JOB_TYPE_SMS);

        List<String> toasts = headersOf(tasks, KanbanProcess.JOB_TYPE_NOTIFY);
        assertThat(toasts).containsExactlyInAnyOrder("CARD_CREATED", "CARD_CREATED", "TARGET_HIT", "CARD_MOVED",
                "CARD_DELETED");
        // email and SMS: created (both columns), deleted, target hit - never moved
        for (String channel : List.of(KanbanProcess.JOB_TYPE_EMAIL, KanbanProcess.JOB_TYPE_SMS)) {
            assertThat(headersOf(tasks, channel)).as(channel)
                    .containsExactlyInAnyOrder("CARD_CREATED", "CARD_CREATED", "CARD_DELETED", "TARGET_HIT");
        }
    }

    private static List<String> headersOf(Collection<ServiceTask> tasks, String jobType) {
        return tasks.stream().filter(t -> type(t).equals(jobType)).map(KanbanCardBpmnTest::header).toList();
    }

    private static FlowNode single(FlowNode node) {
        assertThat(node.getOutgoing()).as("outgoing of " + node.getId()).hasSize(1);
        return node.getOutgoing().iterator().next().getTarget();
    }

    /**
     * Asserts toast -> parallel split -> {email, sms} (same header as the toast) -> parallel join -> next,
     * and returns nothing; ids of email / SMS are checked when given.
     */
    private static void assertToastThenParallelEmailAndSms(String toastId, String emailId, String smsId, String nextId) {
        ServiceTask toast = model.getModelElementById(toastId);
        assertThat(type(toast)).isEqualTo(KanbanProcess.JOB_TYPE_NOTIFY);
        FlowNode split = single(toast);
        assertThat(split).isInstanceOf(ParallelGateway.class);
        List<FlowNode> branches = split.getOutgoing().stream().map(SequenceFlow::getTarget).toList();
        assertThat(branches).extracting(FlowNode::getId).containsExactlyInAnyOrder(emailId, smsId);
        assertThat(branches).allSatisfy(b -> assertThat(header((ServiceTask) b)).isEqualTo(header(toast)));
        assertThat(type(model.getModelElementById(emailId))).isEqualTo(KanbanProcess.JOB_TYPE_EMAIL);
        assertThat(type(model.getModelElementById(smsId))).isEqualTo(KanbanProcess.JOB_TYPE_SMS);

        FlowNode join = single(branches.get(0));
        assertThat(join).isInstanceOf(ParallelGateway.class);
        assertThat(single(branches.get(1))).isSameAs(join);
        assertThat(join.getIncoming()).hasSize(2);
        assertThat(single(join).getId()).isEqualTo(nextId);
    }

    private static ZeebeScript script(String taskId) {
        ScriptTask task = model.getModelElementById(taskId);
        assertThat(task).as(taskId).isNotNull();
        return task.getSingleExtensionElement(ZeebeScript.class);
    }

    @Test
    void targetHitNotifiesOnlyOnceThenSetsStatusNotified() {
        StartEvent start = model.getModelElementById("StartEvent_TargetPriceHit");
        FlowNode check = single(start);
        assertThat(check.getId()).isEqualTo("Gateway_AlreadyNotified");
        assertThat(check).isInstanceOf(ExclusiveGateway.class);

        // already NOTIFIED for this same column + target -> end without any notification
        SequenceFlow duplicate = model.getModelElementById("Flow_AlreadyNotified");
        assertThat(duplicate.getSource()).isSameAs(check);
        assertThat(duplicate.getConditionExpression().getTextContent())
                .isEqualTo("=is defined(status) and status = \"NOTIFIED\" and is defined(notifiedColumn)"
                        + " and notifiedColumn = column and notifiedTargetPrice = targetPrice");
        assertThat(duplicate.getTarget().getId()).isEqualTo("EndEvent_DuplicateIgnored");
        assertThat(duplicate.getTarget().getOutgoing()).isEmpty();

        // first hit (default flow): set status = "NOTIFIED" before notifying
        assertThat(((ExclusiveGateway) check).getDefault().getId()).isEqualTo("Flow_NotYetNotified");
        FlowNode setNotified = ((ExclusiveGateway) check).getDefault().getTarget();
        assertThat(setNotified.getId()).isEqualTo("Task_SetStatusNotified");
        ZeebeScript s = script("Task_SetStatusNotified");
        assertThat(s.getResultVariable()).isEqualTo(KanbanProcess.VAR_STATUS);
        assertThat(s.getExpression()).isEqualTo("=\"" + KanbanProcess.STATUS_NOTIFIED + "\"");
        // ...and remember which column + target it notified
        ScriptTask task = model.getModelElementById("Task_SetStatusNotified");
        assertThat(task.getSingleExtensionElement(ZeebeIoMapping.class).getOutputs())
                .extracting(ZeebeOutput::getSource, ZeebeOutput::getTarget)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("=status", KanbanProcess.VAR_STATUS),
                        org.assertj.core.groups.Tuple.tuple("=column", KanbanProcess.VAR_NOTIFIED_COLUMN),
                        org.assertj.core.groups.Tuple.tuple("=targetPrice", KanbanProcess.VAR_NOTIFIED_TARGET));

        assertThat(single(setNotified).getId()).isEqualTo("Task_NotifyTargetHit");
        assertToastThenParallelEmailAndSms("Task_NotifyTargetHit", "Task_EmailTargetHit", "Task_SmsTargetHit",
                "EndEvent_TargetHitNotified");
    }

    @Test
    void cardMovedReactivatesTheCard() {
        StartEvent start = model.getModelElementById("StartEvent_CardMoved");
        assertThat(single(start).getId()).isEqualTo("Task_SetStatusActive");
        ZeebeScript s = script("Task_SetStatusActive");
        assertThat(s.getResultVariable()).isEqualTo(KanbanProcess.VAR_STATUS);
        assertThat(s.getExpression()).isEqualTo("=\"" + KanbanProcess.STATUS_ACTIVE + "\"");
        assertThat(single(model.getModelElementById("Task_SetStatusActive")).getId()).isEqualTo("Task_NotifyCardMoved");
    }

    @Test
    void cardCreatedSendsToastThenEmailAndSmsInParallelOnBothGatewayBranches() {
        SequenceFlow support = model.getModelElementById("Flow_Support");
        SequenceFlow resistance = model.getModelElementById("Flow_Resistance");
        assertThat(support.getTarget().getId()).isEqualTo("Task_NotifyCreatedSupport");
        assertThat(resistance.getTarget().getId()).isEqualTo("Task_NotifyCreatedResistance");
        assertToastThenParallelEmailAndSms("Task_NotifyCreatedSupport", "Task_EmailCreatedSupport",
                "Task_SmsCreatedSupport", "Gateway_Merge");
        assertToastThenParallelEmailAndSms("Task_NotifyCreatedResistance", "Task_EmailCreatedResistance",
                "Task_SmsCreatedResistance", "Gateway_Merge");
        assertThat((FlowNode) model.getModelElementById("Gateway_Merge")).isInstanceOf(ExclusiveGateway.class);
    }

    @Test
    void cardDeletedSendsToastThenEmailAndSmsInParallelBeforeEnding() {
        IntermediateCatchEvent deleted = model.getModelElementById("Event_CardDeleted");
        assertThat(single(deleted).getId()).isEqualTo("Task_NotifyCardDeleted");
        assertToastThenParallelEmailAndSms("Task_NotifyCardDeleted", KanbanProcess.TASK_EMAIL_CARD_DELETED,
                KanbanProcess.TASK_SMS_CARD_DELETED, "EndEvent_CardDeleted");
    }

    @Test
    void cardMovedIsToastOnly() {
        FlowNode toast = model.getModelElementById("Task_NotifyCardMoved");
        assertThat(single(toast).getId()).isEqualTo("EndEvent_CardMovedNotified");
    }

    @Test
    void parallelGatewaysOnlyAroundEmailAndSms() {
        assertThat(model.getModelElementsByType(ParallelGateway.class)).hasSize(8);
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
