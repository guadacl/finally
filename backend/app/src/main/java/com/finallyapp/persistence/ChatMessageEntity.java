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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * {@code chat_messages}: the AI chat history ({@code role} is {@code user} or {@code assistant}); {@code actions}
 * (trades / watchlist changes the assistant executed) is stored as JSONB and handled here as its JSON text.
 */
@Entity
@Table(name = "chat_messages")
public class ChatMessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", columnDefinition = "text", nullable = false, updatable = false)
    private String userId;

    @Column(name = "role", columnDefinition = "text", nullable = false, updatable = false)
    private String role;

    @Column(name = "content", columnDefinition = "text", nullable = false, updatable = false)
    private String content;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "actions", columnDefinition = "jsonb", updatable = false)
    private String actions;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected ChatMessageEntity() {
    }

    public ChatMessageEntity(String userId, String role, String content, String actionsJson) {
        this.userId = userId;
        this.role = role;
        this.content = content;
        this.actions = actionsJson;
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

    public String getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    /** The actions as JSON text, or null. */
    public String getActions() {
        return actions;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
