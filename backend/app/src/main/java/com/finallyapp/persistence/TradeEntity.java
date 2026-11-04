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

/** {@code trades}: append-only log of executed market orders ({@code side} is {@code buy} or {@code sell}). */
@Entity
@Table(name = "trades")
public class TradeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", columnDefinition = "text", nullable = false, updatable = false)
    private String userId;

    @Column(name = "ticker", columnDefinition = "text", nullable = false, updatable = false)
    private String ticker;

    @Column(name = "side", columnDefinition = "text", nullable = false, updatable = false)
    private String side;

    @Column(name = "quantity", precision = 18, scale = 6, nullable = false, updatable = false)
    private BigDecimal quantity;

    @Column(name = "price", precision = 18, scale = 4, nullable = false, updatable = false)
    private BigDecimal price;

    @Column(name = "executed_at", nullable = false, updatable = false)
    private OffsetDateTime executedAt;

    protected TradeEntity() {
    }

    public TradeEntity(String userId, String ticker, String side, BigDecimal quantity, BigDecimal price) {
        this.userId = userId;
        this.ticker = ticker;
        this.side = side;
        this.quantity = quantity;
        this.price = price;
    }

    @PrePersist
    void onCreate() {
        if (executedAt == null) {
            executedAt = OffsetDateTime.now(ZoneOffset.UTC);
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

    public String getSide() {
        return side;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public OffsetDateTime getExecutedAt() {
        return executedAt;
    }
}
