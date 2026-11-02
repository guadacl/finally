package com.finallyapp.workflow;

import com.finallyapp.contracts.KanbanCardRef;
import com.finallyapp.contracts.KanbanWorkflow;
import com.finallyapp.contracts.NotificationPublisher;
import com.finallyapp.contracts.NotificationType;
import io.camunda.client.CamundaClient;
import io.camunda.client.api.CamundaFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * {@link KanbanWorkflow} backed by the Camunda {@code kanban-card} process.
 *
 * <ul>
 *   <li>{@link #cardCreated} starts an instance (blocking, at most {@link #TIMEOUT}); the gateway in the
 *       process sends the CARD_CREATED notification through the {@code finally-notify} worker.</li>
 *   <li>{@link #cardMoved}, {@link #targetPriceHit}, {@link #cardDeleted} correlate messages (correlation key
 *       = cardId) asynchronously and never block the caller.</li>
 * </ul>
 * Messages are <em>correlated</em> (REST correlate API), not published, so a missing process instance
 * (e.g. a card created while Camunda was down) is reported as an error and triggers the fallback.
 * On any Camunda error the notification is published directly via {@link NotificationPublisher}.
 * No method ever throws.
 */
@Component
@ConditionalOnProperty(name = "finally.workflow.enabled", havingValue = "true", matchIfMissing = true)
public class CamundaKanbanWorkflow implements KanbanWorkflow {

    private static final Logger log = LoggerFactory.getLogger(CamundaKanbanWorkflow.class);

    static final Duration TIMEOUT = Duration.ofSeconds(2);

    private final ObjectProvider<CamundaClient> clientProvider;
    private final DirectKanbanWorkflow fallback;

    public CamundaKanbanWorkflow(ObjectProvider<CamundaClient> clientProvider, NotificationPublisher publisher) {
        this.clientProvider = clientProvider;
        this.fallback = new DirectKanbanWorkflow(publisher);
    }

    @Override
    public Long cardCreated(KanbanCardRef card) {
        try {
            CamundaClient client = client();
            Map<String, Object> vars = new HashMap<>();
            vars.put(KanbanProcess.VAR_CARD_ID, card.id().toString());
            vars.put(KanbanProcess.VAR_TICKER, card.ticker());
            vars.put(KanbanProcess.VAR_COLUMN, card.column().name());
            vars.put(KanbanProcess.VAR_TARGET_PRICE, card.targetPrice());
            CamundaFuture<io.camunda.client.api.response.ProcessInstanceEvent> future = client
                    .newCreateInstanceCommand()
                    .bpmnProcessId(KanbanProcess.PROCESS_ID)
                    .latestVersion()
                    .variables(vars)
                    .requestTimeout(TIMEOUT)
                    .send();
            try {
                long key = future.join(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).getProcessInstanceKey();
                log.debug("Started kanban-card instance {} for card {}", key, card.id());
                return key;
            } catch (RuntimeException e) {
                future.cancel(true);
                throw e;
            }
        } catch (Exception e) {
            log.warn("Camunda unavailable, could not start kanban-card for card {} ({}); notifying directly",
                    card.id(), e.toString());
            fallback.cardCreated(card);
            return null;
        }
    }

    @Override
    public void cardMoved(KanbanCardRef card) {
        Map<String, Object> vars = new HashMap<>();
        vars.put(KanbanProcess.VAR_COLUMN, card.column().name());
        vars.put(KanbanProcess.VAR_TARGET_PRICE, card.targetPrice());
        correlate(KanbanProcess.MSG_CARD_MOVED, card.id(), vars, () -> fallback.cardMoved(card));
    }

    @Override
    public void targetPriceHit(KanbanCardRef card, BigDecimal price) {
        Map<String, Object> vars = new HashMap<>();
        vars.put(KanbanProcess.VAR_PRICE, price);
        correlate(KanbanProcess.MSG_TARGET_PRICE_HIT, card.id(), vars, () -> fallback.targetPriceHit(card, price));
    }

    @Override
    public void cardDeleted(UUID cardId) {
        correlate(KanbanProcess.MSG_CARD_DELETED, cardId, Map.of(), () -> { });
    }

    /** Fire-and-forget message correlation; {@code onFailure} runs (once) if Camunda rejects or is unreachable. */
    private void correlate(String messageName, UUID cardId, Map<String, Object> vars, Runnable onFailure) {
        try {
            client().newCorrelateMessageCommand()
                    .messageName(messageName)
                    .correlationKey(cardId.toString())
                    .variables(vars)
                    .requestTimeout(TIMEOUT)
                    .send()
                    .whenComplete((response, error) -> {
                        if (error != null) {
                            log.warn("Could not correlate '{}' for card {} ({}); falling back",
                                    messageName, cardId, error.toString());
                            runFallback(onFailure);
                        } else {
                            log.debug("Correlated '{}' for card {}", messageName, cardId);
                        }
                    });
        } catch (Exception e) {
            log.warn("Could not send '{}' for card {} ({}); falling back", messageName, cardId, e.toString());
            runFallback(onFailure);
        }
    }

    private static void runFallback(Runnable onFailure) {
        try {
            onFailure.run();
        } catch (Exception e) {
            log.warn("Fallback notification failed: {}", e.toString());
        }
    }

    private CamundaClient client() {
        CamundaClient client = clientProvider.getIfAvailable();
        if (client == null) {
            throw new IllegalStateException("no CamundaClient bean (camunda.client.enabled=false?)");
        }
        return client;
    }
}
