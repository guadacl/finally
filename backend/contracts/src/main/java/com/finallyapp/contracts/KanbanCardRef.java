package com.finallyapp.contracts;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Minimal view of a kanban card passed from the app to the workflow module.
 * {@code userId} is the owner's Keycloak subject; notifications for the card go only to that user.
 */
public record KanbanCardRef(UUID id, String ticker, KanbanColumn column, BigDecimal targetPrice, String userId) {
}
