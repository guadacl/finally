package com.finallyapp.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/** {@code portfolio_snapshots}: total portfolio value over time (every 30 s and after each trade). */
@Entity
@Table(name = "portfolio_snapshots")
public class PortfolioSnapshotEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", columnDefinition = "text", nullable = false, updatable = false)
    private String userId;

    @Column(name = "total_value", precision = 18, scale = 4, nullable = false, updatable = false)
    private BigDecimal totalValue;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private OffsetDateTime recordedAt;

    protected PortfolioSnapshotEntity() {
    }

    public PortfolioSnapshotEntity(String userId, BigDecimal totalValue) {
        this.userId = userId;
        this.totalValue = totalValue;
    }

    @PrePersist
    void onCreate() {
        if (recordedAt == null) {
            recordedAt = OffsetDateTime.now(ZoneOffset.UTC);
        }
    }

    public UUID getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public BigDecimal getTotalValue() {
        return totalValue;
    }

    public OffsetDateTime getRecordedAt() {
        return recordedAt;
    }
}
