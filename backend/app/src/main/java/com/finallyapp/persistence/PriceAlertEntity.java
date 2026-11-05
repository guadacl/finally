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

/** {@code price_alerts}: a watchlist ticker with an upper and/or lower price limit (Alerts page). */
@Entity
@Table(name = "price_alerts")
public class PriceAlertEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", columnDefinition = "text", nullable = false, updatable = false)
    private String userId;

    @Column(name = "ticker", columnDefinition = "text", nullable = false, updatable = false)
    private String ticker;

    @Column(name = "upper_limit", precision = 18, scale = 4)
    private BigDecimal upperLimit;

    @Column(name = "lower_limit", precision = 18, scale = 4)
    private BigDecimal lowerLimit;

    @Column(name = "comment", columnDefinition = "text")
    private String comment;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected PriceAlertEntity() {
    }

    public PriceAlertEntity(String userId, String ticker, BigDecimal upperLimit, BigDecimal lowerLimit, String comment) {
        this.userId = userId;
        this.ticker = ticker;
        this.upperLimit = upperLimit;
        this.lowerLimit = lowerLimit;
        this.comment = comment;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        }
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

    public BigDecimal getUpperLimit() {
        return upperLimit;
    }

    public BigDecimal getLowerLimit() {
        return lowerLimit;
    }

    public String getComment() {
        return comment;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    /** Re-adding a ticker that already has an alert replaces its limits (one alert per ticker). */
    public void setLimits(BigDecimal upperLimit, BigDecimal lowerLimit) {
        this.upperLimit = upperLimit;
        this.lowerLimit = lowerLimit;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }
}
