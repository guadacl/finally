package com.finallyapp.workflow;

import com.finallyapp.contracts.KanbanCardRef;
import com.finallyapp.contracts.KanbanColumn;
import com.finallyapp.contracts.KanbanWorkflow;
import com.finallyapp.contracts.Notification;
import com.finallyapp.contracts.NotificationPublisher;
import io.camunda.client.CamundaClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Boots the module the way the app does (spring.config.import of finally-workflow.yml) and checks which
 * beans are active. The "enabled" case points the client at a closed port, so it also proves that a real
 * Camunda client failing still results in a direct notification within the time budget.
 */
class WorkflowWiringTest {

    @SpringBootApplication
    static class TestApp {
        static final List<Notification> PUBLISHED = new CopyOnWriteArrayList<>();

        @Bean
        NotificationPublisher notificationPublisher() {
            return PUBLISHED::add;
        }
    }

    private ConfigurableApplicationContext start(String... props) {
        String[] base = {
                "--spring.config.import=optional:classpath:finally-workflow.yml",
                "--spring.main.web-application-type=none",
                "--management.health.camunda.enabled=false"
        };
        String[] all = new String[base.length + props.length];
        System.arraycopy(base, 0, all, 0, base.length);
        System.arraycopy(props, 0, all, base.length, props.length);
        return new SpringApplicationBuilder(TestApp.class).run(all);
    }

    @Test
    void disabledUsesDirectWorkflowAndNoCamundaClient() {
        try (ConfigurableApplicationContext ctx = start("--finally.workflow.enabled=false")) {
            assertThat(ctx.getBean(KanbanWorkflow.class)).isInstanceOf(DirectKanbanWorkflow.class);
            assertThat(ctx.getBeanNamesForType(CamundaClient.class)).isEmpty();
            assertThat(ctx.getBeanNamesForType(NotifyJobWorker.class)).isEmpty();
        }
    }

    @Test
    void enabledUsesCamundaWorkflowAndFallsBackFastWhenUnreachable() {
        TestApp.PUBLISHED.clear();
        try (ConfigurableApplicationContext ctx = start(
                "--finally.workflow.enabled=true",
                "--camunda.client.grpc-address=http://127.0.0.1:1",
                "--camunda.client.rest-address=http://127.0.0.1:1")) {
            KanbanWorkflow workflow = ctx.getBean(KanbanWorkflow.class);
            assertThat(workflow).isInstanceOf(CamundaKanbanWorkflow.class);
            assertThat(ctx.getBeanNamesForType(CamundaClient.class)).isNotEmpty();

            KanbanCardRef card = new KanbanCardRef(UUID.randomUUID(), "NVDA", KanbanColumn.RESISTANCE,
                    new BigDecimal("900"));
            long t0 = System.nanoTime();
            assertThat(workflow.cardCreated(card)).isNull();
            workflow.targetPriceHit(card, new BigDecimal("901.5"));
            assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofMillis(3000));

            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(TestApp.PUBLISHED).extracting(Notification::message).containsExactlyInAnyOrder(
                            "New RESISTANCE card: NVDA @ 900.00",
                            "NVDA hit resistance 900.00 (now 901.50)"));
        }
    }
}
