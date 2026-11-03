package com.finallyapp.contracts;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A browser pop-up notification. Serialized as-is to the {@code notification} SSE event
 * (see planning/CONTRACTS.md §8). {@code price} is null for CARD_CREATED / CARD_MOVED when unknown.
 * {@code userId} (the card owner's Keycloak subject) decides which user's browsers receive it.
 */
public record Notification(
        UUID id,
        NotificationType type,
        UUID cardId,
        String ticker,
        KanbanColumn column,
        BigDecimal targetPrice,
        BigDecimal price,
        String message,
        Instant createdAt,
        String userId) {

    public static Notification of(NotificationType type, KanbanCardRef card, BigDecimal price, String message) {
        return new Notification(UUID.randomUUID(), type, card.id(), card.ticker(), card.column(),
                card.targetPrice(), price, message, Instant.now(), card.userId());
    }
}
