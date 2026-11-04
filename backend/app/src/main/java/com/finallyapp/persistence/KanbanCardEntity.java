package com.finallyapp.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.hibernate.annotations.DynamicUpdate;

/**
 * {@code kanban_cards}: a Support / Resistance target-price card ({@code column_name} is {@code SUPPORT} or
 * {@code RESISTANCE}), mirrored by a Camunda {@code kanban-card} process instance. {@link DynamicUpdate}: an
 * update writes only the changed columns (the alert engine flips {@code armed} concurrently from another thread).
 */
@Entity
@Table(name = "kanban_cards")
@DynamicUpdate
public class KanbanCardEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", columnDefinition = "text", nullable = false, updatable = false)
    private String userId;

    @Column(name = "ticker", columnDefinition = "text", nullable = false, updatable = false)
    private String ticker;

    @Column(name = "column_name", columnDefinition = "text", nullable = false)
    private String column;

    @Column(name = "target_price", precision = 18, scale = 4, nullable = false)
    private BigDecimal targetPrice;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "armed", nullable = false)
    private boolean armed = true;

    @Column(name = "process_instance_key")
    private Long processInstanceKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "comment", columnDefinition = "text")
    private String comment;

    protected KanbanCardEntity() {
    }

    public KanbanCardEntity(String userId, String ticker, String column, BigDecimal targetPrice, int sortOrder,
                            String comment) {
        this.userId = userId;
        this.ticker = ticker;
        this.column = column;
        this.targetPrice = targetPrice;
        this.sortOrder = sortOrder;
        this.comment = comment;
    }

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public UUID getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public String getTicker() {
        return ticker;
    }

    public String getColumn() {
        return column;
    }

    public void setColumn(String column) {
        this.column = column;
    }

    public BigDecimal getTargetPrice() {
        return targetPrice;
    }

    public void setTargetPrice(BigDecimal targetPrice) {
        this.targetPrice = targetPrice;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public boolean isArmed() {
        return armed;
    }

    public void setArmed(boolean armed) {
        this.armed = armed;
    }

    public Long getProcessInstanceKey() {
        return processInstanceKey;
    }

    public void setProcessInstanceKey(Long processInstanceKey) {
        this.processInstanceKey = processInstanceKey;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }
}
