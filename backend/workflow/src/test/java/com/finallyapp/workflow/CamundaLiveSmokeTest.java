package com.finallyapp.workflow;

import com.finallyapp.contracts.Notification;
import com.finallyapp.contracts.NotificationType;
import io.camunda.client.CamundaClient;
import io.camunda.client.api.worker.JobWorker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end check of the BPMN against a running Camunda (skipped by default).
 * Run with: {@code docker compose -f camunda/compose.camunda.yml up -d} then
 * {@code mvn -f backend/pom.xml -pl workflow test -Dfinally.it.camunda=true -Dtest=CamundaLiveSmokeTest}.
 */
@EnabledIfSystemProperty(named = "finally.it.camunda", matches = "true")
class CamundaLiveSmokeTest {

    @Test
    void fullCardLifecycle() throws Exception {
        String grpc = System.getProperty("camunda.grpc", "http://localhost:26500");
        String rest = System.getProperty("camunda.rest", "http://localhost:8081");
        List<Notification> received = new CopyOnWriteArrayList<>();

        try (CamundaClient client = CamundaClient.newClientBuilder()
                .grpcAddress(URI.create(grpc)).restAddress(URI.create(rest)).build()) {

            client.newDeployResourceCommand().addResourceFromClasspath("bpmn/kanban-card.bpmn").send().join();

            try (JobWorker ignored = client.newWorker().jobType(KanbanProcess.JOB_TYPE_NOTIFY)
                    .handler((jobClient, job) -> {
                        received.add(NotifyJobWorker.toNotification(job));
                        jobClient.newCompleteCommand(job.getKey()).send().join();
                    })
                    .pollInterval(Duration.ofMillis(100)).open()) {

                String cardId = UUID.randomUUID().toString();
                long key = client.newCreateInstanceCommand().bpmnProcessId(KanbanProcess.PROCESS_ID).latestVersion()
                        .variables(Map.of("cardId", cardId, "ticker", "AAPL", "column", "SUPPORT", "targetPrice", 185))
                        .send().join().getProcessInstanceKey();
                assertThat(key).isPositive();
                await().atMost(Duration.ofSeconds(20)).until(() -> received.size() == 1);
                assertThat(received.get(0).message()).isEqualTo("New Stop Loss card: AAPL @ 185.00");

                // repeated target hits must each produce a notification
                for (double price : new double[] {184.97, 184.5}) {
                    client.newCorrelateMessageCommand().messageName(KanbanProcess.MSG_TARGET_PRICE_HIT)
                            .correlationKey(cardId).variables(Map.of("price", price)).send().join();
                }
                await().atMost(Duration.ofSeconds(20)).until(() -> received.size() == 3);

                client.newCorrelateMessageCommand().messageName(KanbanProcess.MSG_CARD_MOVED).correlationKey(cardId)
                        .variables(Map.of("column", "RESISTANCE", "targetPrice", 200)).send().join();
                await().atMost(Duration.ofSeconds(20)).until(() -> received.size() == 4);

                // after the move, a hit must see the updated column/target
                client.newCorrelateMessageCommand().messageName(KanbanProcess.MSG_TARGET_PRICE_HIT)
                        .correlationKey(cardId).variables(Map.of("price", 200.25)).send().join();
                await().atMost(Duration.ofSeconds(20)).until(() -> received.size() == 5);

                assertThat(received).extracting(Notification::type).containsExactly(
                        NotificationType.CARD_CREATED, NotificationType.TARGET_HIT, NotificationType.TARGET_HIT,
                        NotificationType.CARD_MOVED, NotificationType.TARGET_HIT);
                assertThat(received).extracting(Notification::message).containsExactly(
                        "New Stop Loss card: AAPL @ 185.00",
                        "AAPL hit stop loss 185.00 (now 184.97)",
                        "AAPL hit stop loss 185.00 (now 184.50)",
                        "AAPL moved to Take Profit @ 200.00",
                        "AAPL hit take profit 200.00 (now 200.25)");

                client.newCorrelateMessageCommand().messageName(KanbanProcess.MSG_CARD_DELETED)
                        .correlationKey(cardId).send().join();
                // instance is gone: further correlations are rejected (this is what triggers the app's fallback)
                await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThatThrownBy(() ->
                        client.newCorrelateMessageCommand().messageName(KanbanProcess.MSG_TARGET_PRICE_HIT)
                                .correlationKey(cardId).variables(Map.of("price", 1)).send().join())
                        .isNotNull());
            }
        }
    }
}
