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

    @Test
    void allServiceTasksAreFinallyNotifyWithTypeHeader() {
        Collection<ServiceTask> tasks = model.getModelElementsByType(ServiceTask.class);
        assertThat(tasks).hasSize(4);
        Set<String> types = tasks.stream().map(t -> {
            ZeebeTaskDefinition def = t.getSingleExtensionElement(ZeebeTaskDefinition.class);
            assertThat(def.getType()).isEqualTo(KanbanProcess.JOB_TYPE_NOTIFY);
            ZeebeTaskHeaders headers = t.getSingleExtensionElement(ZeebeTaskHeaders.class);
            return headers.getHeaders().stream()
                    .filter(h -> h.getKey().equals(KanbanProcess.HEADER_NOTIFICATION_TYPE))
                    .map(ZeebeHeader::getValue).findFirst().orElseThrow();
        }).collect(Collectors.toSet());
        assertThat(types).containsExactlyInAnyOrder("CARD_CREATED", "TARGET_HIT", "CARD_MOVED");
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
