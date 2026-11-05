package com.finallyapp.workflow;

/** Names shared with {@code camunda/bpmn/kanban-card.bpmn} (see planning/CONTRACTS.md §6). */
public final class KanbanProcess {

    public static final String PROCESS_ID = "kanban-card";

    public static final String JOB_TYPE_NOTIFY = "finally-notify";
    /** Task header on every {@code finally-notify} service task holding the {@code NotificationType} name. */
    public static final String HEADER_NOTIFICATION_TYPE = "notificationType";
    /**
     * Service tasks after the CARD_CREATED, CARD_DELETED and TARGET_HIT toasts: email the card owner (via the
     * email MCP server). The notification type comes from the same {@link #HEADER_NOTIFICATION_TYPE} header.
     */
    public static final String JOB_TYPE_EMAIL = "email-notify";
    /** Element id of the deletion email task; process versions without it predate deletion emails. */
    public static final String TASK_EMAIL_CARD_DELETED = "Task_EmailCardDeleted";
    /**
     * Service tasks running in parallel with each {@link #JOB_TYPE_EMAIL} task (parallel gateway split/join):
     * SMS to the card owner (via the notification MCP server). Same {@link #HEADER_NOTIFICATION_TYPE} header.
     */
    public static final String JOB_TYPE_SMS = "sms-notify";
    /** Element id of the deletion SMS task; process versions without it predate SMS. */
    public static final String TASK_SMS_CARD_DELETED = "Task_SmsCardDeleted";

    public static final String MSG_CARD_MOVED = "card-moved";
    public static final String MSG_TARGET_PRICE_HIT = "target-price-hit";
    public static final String MSG_CARD_DELETED = "card-deleted";

    public static final String VAR_CARD_ID = "cardId";
    public static final String VAR_TICKER = "ticker";
    public static final String VAR_COLUMN = "column";
    public static final String VAR_TARGET_PRICE = "targetPrice";
    public static final String VAR_PRICE = "price";
    /** Keycloak subject of the card owner; routes notifications to that user only. */
    public static final String VAR_USER_ID = "userId";
    /**
     * Card alert status: {@link #STATUS_ACTIVE} until the first target hit, then {@link #STATUS_NOTIFIED} (set by the
     * BPMN script task {@code Task_SetStatusNotified}); later hits are ignored by {@code Gateway_AlreadyNotified}.
     * Moving the card / editing the target sets it back to ACTIVE ({@code Task_SetStatusActive}).
     */
    public static final String VAR_STATUS = "status";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_NOTIFIED = "NOTIFIED";

    private KanbanProcess() {
    }
}
