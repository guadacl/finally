package com.finallyapp.market;

import com.finallyapp.api.model.TickerSearchResult;
import com.finallyapp.error.ApiException;
import com.finallyapp.error.ErrorCodes;
import com.finallyapp.mail.McpToolClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Full-text stock search by company name or ticker for the watchlist "add" box, answered by the Massive (formerly
 * Polygon.io) MCP server ({@code mcp/massive-mcp-server}): its {@code call_api} tool queries
 * {@code /v3/reference/tickers?search=...} and returns CSV, which is parsed and ranked here.
 *
 * <p>Results are cached per query for {@code finally.search.cache-ttl} (the free Massive plan allows only a few
 * calls per minute), and every name found is handed to {@link TickerNames} so the watchlist shows it at once.
 */
@Service
public class TickerSearchService {

    private static final Logger log = LoggerFactory.getLogger(TickerSearchService.class);
    static final String TOOL = "call_api";
    static final String PATH = "/v3/reference/tickers";
    static final int MAX_RESULTS = 20;
    static final int MAX_QUERY = 50;
    private static final int MAX_CACHED_QUERIES = 500;
    private static final java.util.regex.Pattern TICKER = java.util.regex.Pattern.compile("[A-Z][A-Z0-9.\\-]{0,11}");

    private record Cached(List<TickerSearchResult> results, Instant expires) {
    }

    private final McpToolClient mcp;
    private final TickerNames names;
    private final Duration ttl;
    private final Clock clock;
    private final Map<String, Cached> cache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
            return size() > MAX_CACHED_QUERIES;
        }
    };

    @Autowired
    public TickerSearchService(@Value("${finally.search.mcp-url:}") String mcpUrl,
                               @Value("${finally.search.timeout:15s}") Duration timeout,
                               @Value("${finally.search.cache-ttl:10m}") Duration ttl,
                               TickerNames names) {
        this(new McpToolClient("Massive MCP server", "MASSIVE_MCP_URL", mcpUrl, timeout), names, ttl, Clock.systemUTC());
    }

    TickerSearchService(McpToolClient mcp, TickerNames names, Duration ttl, Clock clock) {
        this.mcp = mcp;
        this.names = names;
        this.ttl = ttl;
        this.clock = clock;
    }

    /** Matching stocks, best first; empty for a blank query. 503 SEARCH_UNAVAILABLE when the search cannot run. */
    public List<TickerSearchResult> search(String raw) {
        String q = raw == null ? "" : raw.trim();
        if (q.isEmpty()) {
            return List.of();
        }
        if (q.length() > MAX_QUERY) {
            throw ApiException.badRequest(ErrorCodes.INVALID_REQUEST, "The search can be at most " + MAX_QUERY + " characters");
        }
        String key = q.toLowerCase(Locale.ROOT);
        synchronized (cache) {
            Cached c = cache.get(key);
            if (c != null && c.expires().isAfter(clock.instant())) {
                return c.results();
            }
        }
        if (!mcp.configured()) {
            throw unavailable("Stock search is not configured. Add the stock by its ticker instead.");
        }
        String csv;
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("search", q);
            params.put("market", "stocks");
            params.put("active", "true");
            params.put("limit", 100);
            McpSchema.CallToolResult result = mcp.call(TOOL, Map.of("path", PATH, "params", params));
            csv = text(result);
        } catch (McpToolClient.ToolException e) {
            log.warn("Ticker search for '{}' failed: {}", q, e.getMessage());
            throw unavailable("Stock search is unavailable right now. Add the stock by its ticker instead.");
        }
        if (csv.startsWith("Error")) {
            log.warn("Ticker search for '{}' returned an error: {}", q, csv.lines().findFirst().orElse(""));
            throw unavailable(csv.contains("429")
                    ? "Too many searches: wait a minute and try again, or add the stock by its ticker."
                    : "Stock search is unavailable right now. Add the stock by its ticker instead.");
        }
        List<TickerSearchResult> results = rank(q, parse(csv));
        results.forEach(r -> names.remember(r.getTicker(), r.getName()));
        synchronized (cache) {
            cache.put(key, new Cached(results, clock.instant().plus(ttl)));
        }
        return results;
    }

    /** The CSV the tool returned: structuredContent.result when present, else the text content. */
    static String text(McpSchema.CallToolResult result) {
        if (result.structuredContent() instanceof Map<?, ?> map && map.get("result") instanceof String s) {
            return s.trim();
        }
        return McpToolClient.text(result).trim();
    }

    /**
     * Parses the tool's CSV ({@code ticker,name,market,locale,primary_exchange,type,active,...} with a header row);
     * stops at the first blank line (a pagination hint may follow). A "Warning" (e.g. no records) gives no rows.
     */
    static List<TickerSearchResult> parse(String csv) {
        List<TickerSearchResult> out = new ArrayList<>();
        if (csv == null || csv.isBlank() || csv.startsWith("Warning")) {
            return out;
        }
        List<List<String>> rows = csvRows(csv);
        if (rows.isEmpty()) {
            return out;
        }
        List<String> header = rows.get(0);
        int iTicker = header.indexOf("ticker");
        int iName = header.indexOf("name");
        int iExchange = header.indexOf("primary_exchange");
        int iType = header.indexOf("type");
        int iActive = header.indexOf("active");
        if (iTicker < 0 || iName < 0) {
            return out;
        }
        for (List<String> row : rows.subList(1, rows.size())) {
            String ticker = cell(row, iTicker);
            String name = cell(row, iName);
            if (ticker == null || name == null || "false".equalsIgnoreCase(cell(row, iActive))
                    || !TICKER.matcher(ticker.toUpperCase(Locale.ROOT)).matches()) {
                continue; // inactive, incomplete, or not a row at all (e.g. a "Next page available..." hint)
            }
            out.add(new TickerSearchResult(ticker.toUpperCase(Locale.ROOT), name)
                    .exchange(cell(row, iExchange))
                    .type(cell(row, iType)));
        }
        return out;
    }

    /** Best match first: exact ticker, ticker prefix, name prefix, then anything else; ties by shorter ticker. */
    static List<TickerSearchResult> rank(String query, List<TickerSearchResult> found) {
        String q = query.trim().toUpperCase(Locale.ROOT);
        Map<String, TickerSearchResult> unique = new LinkedHashMap<>();
        found.forEach(r -> unique.putIfAbsent(r.getTicker(), r));
        return unique.values().stream()
                .sorted(Comparator.<TickerSearchResult>comparingInt(r -> score(q, r))
                        .thenComparingInt(r -> r.getTicker().length())
                        .thenComparing(TickerSearchResult::getTicker))
                .limit(MAX_RESULTS)
                .toList();
    }

    private static int score(String q, TickerSearchResult r) {
        String ticker = r.getTicker();
        String name = r.getName().toUpperCase(Locale.ROOT);
        if (ticker.equals(q)) {
            return 0;
        }
        if (ticker.startsWith(q)) {
            return 1;
        }
        if (name.startsWith(q)) {
            return 2;
        }
        if (name.contains(" " + q)) {
            return 3;
        }
        return 4;
    }

    private static String cell(List<String> row, int i) {
        if (i < 0 || i >= row.size()) {
            return null;
        }
        String v = row.get(i).trim();
        return v.isEmpty() ? null : v;
    }

    /** RFC 4180-style rows: quoted fields may contain commas, doubled quotes and newlines. */
    static List<List<String>> csvRows(String csv) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean any = false;
        for (int i = 0; i < csv.length(); i++) {
            char ch = csv.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < csv.length() && csv.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(ch);
                }
                continue;
            }
            switch (ch) {
                case '"' -> {
                    quoted = true;
                    any = true;
                }
                case ',' -> {
                    row.add(field.toString());
                    field.setLength(0);
                    any = true;
                }
                case '\r' -> {
                    // ignored (CRLF)
                }
                case '\n' -> {
                    if (!any && field.isEmpty()) {
                        return rows; // blank line: end of the table
                    }
                    row.add(field.toString());
                    rows.add(row);
                    row = new ArrayList<>();
                    field.setLength(0);
                    any = false;
                }
                default -> {
                    field.append(ch);
                    any = true;
                }
            }
        }
        if (any || !field.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }

    private static ApiException unavailable(String message) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.SEARCH_UNAVAILABLE, message);
    }
}
