package com.finallyapp.workflow;

import com.finallyapp.contracts.KanbanCardRef;
import com.finallyapp.contracts.KanbanWorkflow;
import com.finallyapp.contracts.NotificationEmailer;
import com.finallyapp.contracts.NotificationPublisher;
import com.finallyapp.contracts.NotificationSms;
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
 * checked (cached per definition) and, for such old versions, the missing deletion toast / email / SMS are sent
 * directly.
 */
@Component
@ConditionalOnProperty(name = "finally.workflow.enabled", havingValue = "true", matchIfMissing = true)
public class CamundaKanbanWorkflow implements KanbanWorkflow {

    private static final Logger log = LoggerFactory.getLogger(CamundaKanbanWorkflow.class);

    static final Duration TIMEOUT = Duration.ofSeconds(2);
    static final Duration LOOKUP_TIMEOUT = Duration.ofSeconds(5);

    private final ObjectProvider<CamundaClient> clientProvider;
    private final DirectKanbanWorkflow fallback;
    /** process definition key -> what its BPMN does on card deletion. */
    private final Map<Long, DeleteSupport> deleteSupport = new ConcurrentHashMap<>();
    private final Executor background;

    public CamundaKanbanWorkflow(ObjectProvider<CamundaClient> clientProvider, NotificationPublisher publisher) {
        this(clientProvider, new DirectKanbanWorkflow(publisher), defaultExecutor());
    }

    /**
     * The fallback also emails and texts (the email-notify / sms-notify tasks do not run when Camunda could not be
     * reached).
     */
    @Autowired
    public CamundaKanbanWorkflow(ObjectProvider<CamundaClient> clientProvider, NotificationPublisher publisher,
                                 ObjectProvider<NotificationEmailer> emailer, ObjectProvider<NotificationSms> sms) {
        this(clientProvider, new DirectKanbanWorkflow(publisher, emailer, sms), defaultExecutor());
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
            vars.put(KanbanProcess.VAR_STATUS, KanbanProcess.STATUS_ACTIVE);
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
        // moved / new target: the card is re-armed (the BPMN's Task_SetStatusActive also sets it)
        vars.put(KanbanProcess.VAR_STATUS, KanbanProcess.STATUS_ACTIVE);
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

    /** What a process version does on card deletion. */
    record DeleteSupport(boolean emails, boolean texts) {
        static DeleteSupport of(String bpmnXml) {
            return new DeleteSupport(bpmnXml.contains(KanbanProcess.TASK_EMAIL_CARD_DELETED),
                    bpmnXml.contains(KanbanProcess.TASK_SMS_CARD_DELETED));
        }
    }

    /**
     * Completes the deletion notifications for instances of older process versions: a version without the
     * deletion email task gets toast + email + SMS sent directly; a version with the email but without the SMS
     * task (email-only era) gets just the SMS.
     */
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
            DeleteSupport support = deleteSupport.computeIfAbsent(definitionKey, k -> DeleteSupport.of(client
                    .newProcessDefinitionGetXmlRequest(k).send()
                    .join(LOOKUP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)));
            if (!support.emails()) {
                log.info("Card {} ran a kanban-card version without deletion email/SMS; notifying directly",
                        card.id());
                fallback.cardDeleted(card, instanceKey);
            } else if (!support.texts()) {
                log.info("Card {} ran a kanban-card version without the deletion SMS; sending it directly", card.id());
                fallback.cardDeletedSmsOnly(card);
            }
        } catch (Exception e) {
            // unknown: assume the current version (which emails and texts) rather than risk duplicates
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
