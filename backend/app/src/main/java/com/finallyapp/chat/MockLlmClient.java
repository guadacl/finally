package com.finallyapp.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic, offline LLM used when {@code LLM_MOCK=true} (E2E tests, development without a key).
 * <ul>
 *   <li>"buy 5 TSLA" → buys 5 TSLA; any other message containing "buy" → buys 1 AAPL</li>
 *   <li>"sell 2 AAPL" → sells 2 AAPL; any other message containing "sell" → sells 1 AAPL</li>
 *   <li>"add PYPL" / "watch PYPL" → adds PYPL to the watchlist; "remove PYPL" → removes it</li>
 *   <li>anything else → a fixed analysis message, no actions</li>
 * </ul>
 */
public class MockLlmClient implements LlmClient {

    private static final Pattern TRADE =
            Pattern.compile("\\b(buy|sell)\\s+(\\d+(?:\\.\\d+)?)\\s+(?:shares?\\s+(?:of\\s+)?)?([A-Za-z][A-Za-z0-9.\\-]{0,9})\\b",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern WATCH =
            Pattern.compile("\\b(add|watch|remove|unwatch)\\s+([A-Za-z][A-Za-z0-9.\\-]{0,9})\\b",
                    Pattern.CASE_INSENSITIVE);

    private final ObjectMapper mapper;

    public MockLlmClient(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String complete(List<Message> messages) {
        String user = "";
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).role().equals("user")) {
                user = messages.get(i).content();
                break;
            }
        }
        try {
            return mapper.writeValueAsString(respond(user));
        } catch (Exception e) {
            throw new LlmException("mock serialization failed", e);
        }
    }

    Map<String, Object> respond(String userMessage) {
        String text = userMessage == null ? "" : userMessage;
        String lower = text.toLowerCase(Locale.ROOT);
        List<Map<String, Object>> trades = new ArrayList<>();
        List<Map<String, Object>> changes = new ArrayList<>();
        List<String> said = new ArrayList<>();

        Matcher t = TRADE.matcher(text);
        if (t.find()) {
            String side = t.group(1).toLowerCase(Locale.ROOT);
            String ticker = t.group(3).toUpperCase(Locale.ROOT);
            double qty = Double.parseDouble(t.group(2));
            trades.add(trade(ticker, side, qty));
            said.add((side.equals("buy") ? "Buying " : "Selling ") + t.group(2) + " " + ticker + ".");
        } else if (lower.contains("buy")) {
            trades.add(trade("AAPL", "buy", 1));
            said.add("Buying 1 AAPL.");
        } else if (lower.contains("sell")) {
            trades.add(trade("AAPL", "sell", 1));
            said.add("Selling 1 AAPL.");
        }

        Matcher w = WATCH.matcher(text);
        while (w.find()) {
            String verb = w.group(1).toLowerCase(Locale.ROOT);
            String ticker = w.group(2).toUpperCase(Locale.ROOT);
            if (ticker.equals("TO") || ticker.equals("FROM") || ticker.equals("THE") || ticker.equals("MY")) {
                continue;
            }
            String action = verb.equals("add") || verb.equals("watch") ? "add" : "remove";
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("ticker", ticker);
            c.put("action", action);
            changes.add(c);
            said.add((action.equals("add") ? "Adding " : "Removing ") + ticker
                    + (action.equals("add") ? " to" : " from") + " your watchlist.");
        }

        String message = said.isEmpty()
                ? "Mock FinAlly here. Your portfolio is diversified across your holdings; "
                  + "ask me to buy or sell (e.g. \"buy 5 TSLA\") or to add a ticker to your watchlist."
                : String.join(" ", said);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("message", message);
        out.put("trades", trades);
        out.put("watchlist_changes", changes);
        return out;
    }

    private static Map<String, Object> trade(String ticker, String side, double qty) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ticker", ticker);
        m.put("side", side);
        m.put("quantity", qty);
        return m;
    }
}
