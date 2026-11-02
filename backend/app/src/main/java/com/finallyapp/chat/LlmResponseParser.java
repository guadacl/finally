package com.finallyapp.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lenient parser for the LLM's structured JSON. Never throws: malformed output becomes a graceful message,
 * malformed individual actions are dropped. Tolerates markdown code fences and text around the JSON object.
 */
public final class LlmResponseParser {

    private static final Logger log = LoggerFactory.getLogger(LlmResponseParser.class);
    static final String FALLBACK =
            "Sorry, I couldn't process that request properly (the AI returned an unexpected response). Please try again.";

    private final ObjectMapper mapper;

    public LlmResponseParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public LlmDecision parse(String content) {
        if (content == null || content.isBlank()) {
            return LlmDecision.messageOnly(FALLBACK);
        }
        JsonNode root = readObject(content.trim());
        if (root == null) {
            log.warn("Malformed LLM output (not JSON): {}", abbreviate(content));
            return LlmDecision.messageOnly(FALLBACK);
        }
        JsonNode msgNode = root.path("message");
        String message = msgNode.isTextual() ? msgNode.asText().trim() : "";

        List<LlmDecision.TradeIntent> trades = new ArrayList<>();
        for (JsonNode t : array(root, "trades")) {
            String ticker = text(t, "ticker");
            String side = text(t, "side");
            JsonNode q = t.path("quantity");
            BigDecimal qty = null;
            if (q.isNumber()) {
                qty = q.decimalValue();
            } else if (q.isTextual()) {
                try {
                    qty = new BigDecimal(q.asText().trim());
                } catch (NumberFormatException ignored) {
                    // dropped below
                }
            }
            if (ticker == null || side == null || qty == null) {
                log.warn("Dropping malformed trade from LLM: {}", t);
                continue;
            }
            side = side.toLowerCase(Locale.ROOT);
            if (!side.equals("buy") && !side.equals("sell")) {
                log.warn("Dropping trade with invalid side from LLM: {}", t);
                continue;
            }
            trades.add(new LlmDecision.TradeIntent(ticker, side, qty));
        }

        List<LlmDecision.WatchlistIntent> changes = new ArrayList<>();
        JsonNode wl = root.has("watchlist_changes") ? root.path("watchlist_changes") : root.path("watchlistChanges");
        if (wl.isArray()) {
            for (JsonNode w : wl) {
                String ticker = text(w, "ticker");
                String action = text(w, "action");
                if (ticker == null || action == null) {
                    continue;
                }
                action = action.toLowerCase(Locale.ROOT);
                if (!action.equals("add") && !action.equals("remove")) {
                    continue;
                }
                changes.add(new LlmDecision.WatchlistIntent(ticker, action));
            }
        }
        if (message.isEmpty()) {
            message = trades.isEmpty() && changes.isEmpty() ? FALLBACK : "Done.";
        }
        return new LlmDecision(message, trades, changes);
    }

    private JsonNode readObject(String s) {
        String candidate = stripFences(s);
        JsonNode n = tryRead(candidate);
        if (n == null) {
            int start = candidate.indexOf('{');
            int end = candidate.lastIndexOf('}');
            if (start >= 0 && end > start) {
                n = tryRead(candidate.substring(start, end + 1));
            }
        }
        return n != null && n.isObject() ? n : null;
    }

    private JsonNode tryRead(String s) {
        try {
            return mapper.readTree(s);
        } catch (Exception e) {
            return null;
        }
    }

    private static String stripFences(String s) {
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            int end = s.lastIndexOf("```");
            if (nl > 0 && end > nl) {
                return s.substring(nl + 1, end).trim();
            }
        }
        return s;
    }

    private static Iterable<JsonNode> array(JsonNode root, String field) {
        JsonNode a = root.path(field);
        return a.isArray() ? a : List.of();
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        if (!v.isTextual() || v.asText().isBlank()) {
            return null;
        }
        return v.asText().trim();
    }

    private static String abbreviate(String s) {
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}
