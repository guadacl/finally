package com.finallyapp.contracts;

import java.math.BigDecimal;

/**
 * Drives the Camunda {@code kanban-card} process for each kanban card.
 * Implemented by the workflow module; called by the app.
 *
 * <p>Implementations must never throw to the caller: on Camunda errors they log and fall back to
 * publishing the matching {@link Notification} directly via {@link NotificationPublisher}.
 */
public interface KanbanWorkflow {

    /**
     * Starts a process instance for a newly created card (sends the CARD_CREATED notification via the gateway).
     *
     * @return the Camunda process instance key, or {@code null} if Camunda was unavailable
     */
    Long cardCreated(KanbanCardRef card);

    /** Card moved to another column and/or target price edited (correlates message {@code card-moved}). */
    void cardMoved(KanbanCardRef card);

    /** Live price reached the card's target (correlates message {@code target-price-hit}). */
    void targetPriceHit(KanbanCardRef card, BigDecimal price);

    /**
     * Card deleted (correlates message {@code card-deleted} with the card's last ticker / column / target / owner;
     * the process then sends the CARD_DELETED toast and email and ends). Call with the values read
     * <em>before</em> the database row was removed. {@code processInstanceKey} (may be null) lets the
     * implementation cancel the instance if the message cannot be correlated.
     */
    void cardDeleted(KanbanCardRef card, Long processInstanceKey);

    /**
     * Silently cancels the process instance of a card that was removed by a data cleanup (a duplicate ticker in
     * the same column), without the CARD_DELETED toast or email. Best effort; no-op without Camunda.
     */
    default void processDiscarded(long processInstanceKey) {
    }
}
