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

/** {@code positions}: a user's current holding of a ticker (one row per user and ticker). */
@Entity
@Table(name = "positions")
public class PositionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", columnDefinition = "text", nullable = false, updatable = false)
    private String userId;

    @Column(name = "ticker", columnDefinition = "text", nullable = false, updatable = false)
    private String ticker;

    @Column(name = "quantity", precision = 18, scale = 6, nullable = false)
    private BigDecimal quantity;

    @Column(name = "avg_cost", precision = 18, scale = 4, nullable = false)
    private BigDecimal avgCost;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected PositionEntity() {
    }

    public PositionEntity(String userId, String ticker) {
        this.userId = userId;
        this.ticker = ticker;
    }

    @PrePersist
    @PreUpdate
    void touch() {
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

    public BigDecimal getQuantity() {
        return quantity;
    }

    public void setQuantity(BigDecimal quantity) {
        this.quantity = quantity;
    }

    public BigDecimal getAvgCost() {
        return avgCost;
    }

    public void setAvgCost(BigDecimal avgCost) {
        this.avgCost = avgCost;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
