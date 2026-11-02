package com.finallyapp.workflow;

/** Names shared with {@code camunda/bpmn/kanban-card.bpmn} (see planning/CONTRACTS.md §6). */
public final class KanbanProcess {

    public static final String PROCESS_ID = "kanban-card";

    public static final String JOB_TYPE_NOTIFY = "finally-notify";
    /** Task header on every {@code finally-notify} service task holding the {@code NotificationType} name. */
    public static final String HEADER_NOTIFICATION_TYPE = "notificationType";

    public static final String MSG_CARD_MOVED = "card-moved";
    public static final String MSG_TARGET_PRICE_HIT = "target-price-hit";
    public static final String MSG_CARD_DELETED = "card-deleted";

    public static final String VAR_CARD_ID = "cardId";
    public static final String VAR_TICKER = "ticker";
    public static final String VAR_COLUMN = "column";
    public static final String VAR_TARGET_PRICE = "targetPrice";
    public static final String VAR_PRICE = "price";

    private KanbanProcess() {
    }
}
