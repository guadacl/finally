package com.finallyapp.kanban;

import com.finallyapp.contracts.KanbanColumn;
import java.math.BigDecimal;

/** Pure alert rules (CONTRACTS.md §5). */
public final class AlertRules {

    public enum Decision { FIRE, NONE }

    /** Card status shown to the user and mirrored in the Camunda process variable {@code status}. */
    public enum Status { ACTIVE, NOTIFIED }

    private AlertRules() {
    }

    /** SUPPORT is hit when price &lt;= target, RESISTANCE when price &gt;= target. */
    public static boolean isHit(KanbanColumn column, BigDecimal target, BigDecimal price) {
        int c = price.compareTo(target);
        return column == KanbanColumn.SUPPORT ? c <= 0 : c >= 0;
    }

    /**
     * Fire exactly once: an ACTIVE ({@code armed}) card fires on its first hit and becomes NOTIFIED. It does
     * <em>not</em> re-arm when the price moves back; only the user re-arms it by editing the target or moving the
     * card ({@code KanbanService.update}).
     */
    public static Decision evaluate(KanbanColumn column, BigDecimal target, boolean armed, BigDecimal price) {
        return armed && isHit(column, target, price) ? Decision.FIRE : Decision.NONE;
    }

    public static Status status(boolean armed) {
        return armed ? Status.ACTIVE : Status.NOTIFIED;
    }
}
