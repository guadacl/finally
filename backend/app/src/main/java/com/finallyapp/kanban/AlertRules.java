package com.finallyapp.kanban;

import com.finallyapp.contracts.KanbanColumn;
import java.math.BigDecimal;

/** Pure alert rules (CONTRACTS.md §5). */
public final class AlertRules {

    public enum Decision { FIRE, REARM, NONE }

    private AlertRules() {
    }

    /** SUPPORT is hit when price &lt;= target, RESISTANCE when price &gt;= target. */
    public static boolean isHit(KanbanColumn column, BigDecimal target, BigDecimal price) {
        int c = price.compareTo(target);
        return column == KanbanColumn.SUPPORT ? c <= 0 : c >= 0;
    }

    /** Fire once while armed; re-arm when the condition is false again. */
    public static Decision evaluate(KanbanColumn column, BigDecimal target, boolean armed, BigDecimal price) {
        boolean hit = isHit(column, target, price);
        if (hit && armed) {
            return Decision.FIRE;
        }
        if (!hit && !armed) {
            return Decision.REARM;
        }
        return Decision.NONE;
    }
}
