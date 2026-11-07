package com.finallyapp.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * {@code kanban_discarded_processes}: Camunda process instances of duplicate cards removed by migration V7,
 * waiting to be cancelled silently at startup (see {@code DiscardedProcessCanceller}).
 */
@Entity
@Table(name = "kanban_discarded_processes")
public class KanbanDiscardedProcessEntity {

    @Id
    @Column(name = "process_instance_key", nullable = false, updatable = false)
    private Long processInstanceKey;

    @Column(name = "card_id", nullable = false, updatable = false)
    private UUID cardId;

    @Column(name = "discarded_at", nullable = false, updatable = false)
    private OffsetDateTime discardedAt;

    protected KanbanDiscardedProcessEntity() {
    }

    public Long getProcessInstanceKey() {
        return processInstanceKey;
    }

    public UUID getCardId() {
        return cardId;
    }

    public OffsetDateTime getDiscardedAt() {
        return discardedAt;
    }
}
