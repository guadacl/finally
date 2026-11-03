package com.finallyapp.workflow;

import com.finallyapp.contracts.KanbanCardRef;
import com.finallyapp.contracts.KanbanWorkflow;
import com.finallyapp.contracts.NotificationEmailer;
import com.finallyapp.contracts.NotificationPublisher;
import io.camunda.client.CamundaClient;
import io.camunda.client.api.CamundaFuture;
import io.camunda.client.api.response.CorrelateMessageResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * {@link KanbanWorkflow} backed by the Camunda {@code kanban-card} process.
 *
 * <ul>
 *   <li>{@link #cardCreated} starts an instance (blocking, at most {@link #TIMEOUT}); the gateway in the
 *       process sends the CARD_CREATED toast ({@code finally-notify}) and email ({@code email-notify}).</li>
 *   <li>{@link #cardMoved}, {@link #targetPriceHit}, {@link #cardDeleted} correlate messages (correlation key
 *       = cardId) asynchronously and never block the caller.</li>
 * </ul>
 * Messages are <em>correlated</em> (REST correlate API), not published, so a missing process instance
 * (e.g. a card created while Camunda was down) is reported as an error and triggers the fallback, which
 * publishes the toast (and email) directly. A card that cannot be correlated on delete is also cancelled by its
 * process instance key. No method ever throws.
 *
 * <p>Process instances started before the deletion email existed run an older process version that ends
 * without emailing; after a successful {@code card-deleted} correlation the instance's process definition is
 * checked (cached per definition) and, for such old versions, the deletion toast + email are sent directly.
 */
@Component
@ConditionalOnProperty(name = "finally.workflow.enabled", havingValue = "true", matchIfMissing = true)
public class CamundaKanbanWorkflow implements KanbanWorkflow {

    private static final Logger log = LoggerFactory.getLogger(CamundaKanbanWorkflow.class);

    static final Duration TIMEOUT = Duration.ofSeconds(2);
    static final Duration LOOKUP_TIMEOUT = Duration.ofSeconds(5);

    private final ObjectProvider<CamundaClient> clientProvider;
    private final DirectKanbanWorkflow fallback;
    /** process definition key -> its BPMN emails on card deletion. */
    private final Map<Long, Boolean> emailsOnDelete = new ConcurrentHashMap<>();
    private final Executor background;

    public CamundaKanbanWorkflow(ObjectProvider<CamundaClient> clientProvider, NotificationPublisher publisher) {
        this(clientProvider, new DirectKanbanWorkflow(publisher), defaultExecutor());
    }

    /** The fallback also emails (the email-notify tasks do not run when Camunda could not be reached). */
    @Autowired
    public CamundaKanbanWorkflow(ObjectProvider<CamundaClient> clientProvider, NotificationPublisher publisher,
                                 ObjectProvider<NotificationEmailer> emailer) {
        this(clientProvider, new DirectKanbanWorkflow(publisher, emailer), defaultExecutor());
    }

    CamundaKanbanWorkflow(ObjectProvider<CamundaClient> clientProvider, DirectKanbanWorkflow fallback,
                          Executor background) {
        this.clientProvider = clientProvider;
        this.fallback = fallback;
        this.background = background;
    }

    private static Executor defaultExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "finally-workflow-lookup");
            t.setDaemon(true);
            return t;
        });
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
            vars.put(KanbanProcess.VAR_USER_ID, card.userId());
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
        correlate(KanbanProcess.MSG_CARD_MOVED, card.id(), vars, null, () -> fallback.cardMoved(card));
    }

    @Override
    public void targetPriceHit(KanbanCardRef card, BigDecimal price) {
        Map<String, Object> vars = new HashMap<>();
        vars.put(KanbanProcess.VAR_PRICE, price);
        correlate(KanbanProcess.MSG_TARGET_PRICE_HIT, card.id(), vars, null,
                () -> fallback.targetPriceHit(card, price));
    }

    @Override
    public void cardDeleted(KanbanCardRef card, Long processInstanceKey) {
        // the card's last state travels with the message: the process uses it for the deletion toast + email
        Map<String, Object> vars = new HashMap<>();
        vars.put(KanbanProcess.VAR_TICKER, card.ticker());
        vars.put(KanbanProcess.VAR_COLUMN, card.column().name());
        vars.put(KanbanProcess.VAR_TARGET_PRICE, card.targetPrice());
        vars.put(KanbanProcess.VAR_USER_ID, card.userId());
        correlate(KanbanProcess.MSG_CARD_DELETED, card.id(), vars,
                response -> background.execute(() -> notifyIfLegacyVersion(card, response)),
                () -> {
                    fallback.cardDeleted(card, processInstanceKey);
                    cancel(card, processInstanceKey);
                });
    }

    /** For instances of a process version without the deletion email task: send toast + email directly. */
    void notifyIfLegacyVersion(KanbanCardRef card, CorrelateMessageResponse response) {
        try {
            Long instanceKey = response == null ? null : response.getProcessInstanceKey();
            if (instanceKey == null) {
                return;
            }
            CamundaClient client = client();
            Long definitionKey = client.newProcessInstanceGetRequest(instanceKey).send()
                    .join(LOOKUP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).getProcessDefinitionKey();
            if (definitionKey == null) {
                return;
            }
            boolean emails = emailsOnDelete.computeIfAbsent(definitionKey, k -> client
                    .newProcessDefinitionGetXmlRequest(k).send()
                    .join(LOOKUP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                    .contains(KanbanProcess.TASK_EMAIL_CARD_DELETED));
            if (!emails) {
                log.info("Card {} ran an older kanban-card version without the deletion email; notifying directly",
                        card.id());
                fallback.cardDeleted(card, instanceKey);
            }
        } catch (Exception e) {
            // unknown: assume the current version (which emails) rather than risk a duplicate email
            log.debug("Could not check the process version of card {}: {}", card.id(), e.toString());
        }
    }

    private void cancel(KanbanCardRef card, Long processInstanceKey) {
        if (processInstanceKey == null) {
            return;
        }
        try {
            client().newCancelInstanceCommand(processInstanceKey).requestTimeout(TIMEOUT).send()
                    .whenComplete((r, error) -> {
                        if (error != null) {
                            log.debug("Could not cancel instance {} of deleted card {}: {}", processInstanceKey,
                                    card.id(), error.toString());
                        } else {
                            log.info("Cancelled instance {} of deleted card {}", processInstanceKey, card.id());
                        }
                    });
        } catch (Exception e) {
            log.debug("Could not cancel instance {} of deleted card {}: {}", processInstanceKey, card.id(),
                    e.toString());
        }
    }

    /**
     * Fire-and-forget message correlation; {@code onFailure} runs (once) if Camunda rejects or is unreachable,
     * {@code onSuccess} (optional) gets the correlation response.
     */
    private void correlate(String messageName, UUID cardId, Map<String, Object> vars,
                           Consumer<CorrelateMessageResponse> onSuccess, Runnable onFailure) {
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
                            if (onSuccess != null) {
                                try {
                                    onSuccess.accept(response);
                                } catch (Exception e) {
                                    log.warn("After correlating '{}' for card {}: {}", messageName, cardId,
                                            e.toString());
                                }
                            }
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
