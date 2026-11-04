package com.finallyapp.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/** {@code watchlist}: a ticker on a user's watchlist (unique per user and ticker). */
@Entity
@Table(name = "watchlist")
public class WatchlistEntryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", columnDefinition = "text", nullable = false, updatable = false)
    private String userId;

    @Column(name = "ticker", columnDefinition = "text", nullable = false, updatable = false)
    private String ticker;

    @Column(name = "added_at", nullable = false, updatable = false)
    private OffsetDateTime addedAt;

    protected WatchlistEntryEntity() {
    }

    public WatchlistEntryEntity(String userId, String ticker) {
        this.userId = userId;
        this.ticker = ticker;
    }

    @PrePersist
    void onCreate() {
        if (addedAt == null) {
            addedAt = OffsetDateTime.now(ZoneOffset.UTC);
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

    public OffsetDateTime getAddedAt() {
        return addedAt;
    }
}
