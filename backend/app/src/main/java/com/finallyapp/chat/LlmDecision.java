package com.finallyapp.chat;

import java.math.BigDecimal;
import java.util.List;

/** The structured output requested from the LLM (CONTRACTS.md §9). */
public record LlmDecision(String message, List<TradeIntent> trades, List<WatchlistIntent> watchlistChanges) {

    public record TradeIntent(String ticker, String side, BigDecimal quantity) {
    }

    public record WatchlistIntent(String ticker, String action) {
    }

    public LlmDecision {
        trades = trades == null ? List.of() : List.copyOf(trades);
        watchlistChanges = watchlistChanges == null ? List.of() : List.copyOf(watchlistChanges);
    }

    public static LlmDecision messageOnly(String message) {
        return new LlmDecision(message, List.of(), List.of());
    }
}
