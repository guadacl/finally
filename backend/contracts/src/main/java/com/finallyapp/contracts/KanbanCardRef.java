package com.finallyapp.contracts;

import java.math.BigDecimal;
import java.util.UUID;

/** Minimal view of a kanban card passed from the app to the workflow module. */
public record KanbanCardRef(UUID id, String ticker, KanbanColumn column, BigDecimal targetPrice) {
}
