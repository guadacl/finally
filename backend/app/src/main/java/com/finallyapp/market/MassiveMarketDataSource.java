package com.finallyapp.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

/**
 * {@link MarketDataSource} backed by the Massive (formerly Polygon.io) REST API.
 * Polls {@code GET /v2/snapshot/locale/us/markets/stocks/tickers?tickers=...} for all tracked tickers in one
 * call (free tier: every 15 s) and writes the last-trade price of each snapshot into the {@link PriceCache}.
 */
public class MassiveMarketDataSource implements MarketDataSource {

    private static final Logger log = LoggerFactory.getLogger(MassiveMarketDataSource.class);
    static final String SNAPSHOT_PATH = "/v2/snapshot/locale/us/markets/stocks/tickers";

    /** One parsed snapshot. */
    public record Snapshot(String ticker, double price, Instant timestamp) {
    }

    private final PriceCache cache;
    private final RestClient client;
    private final Duration pollInterval;
    private final ObjectMapper mapper;
    private final List<String> tickers = new CopyOnWriteArrayList<>();
    private ScheduledExecutorService executor;

    public MassiveMarketDataSource(PriceCache cache, RestClient.Builder builder, String baseUrl, String apiKey,
                                   Duration pollInterval, ObjectMapper mapper) {
        this.cache = cache;
        this.pollInterval = pollInterval;
        this.mapper = mapper;
        this.client = builder.baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
    }

    @Override
    public synchronized void start(Collection<String> initial) {
        if (executor != null) {
            return;
        }
        initial.forEach(this::addTicker);
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread th = new Thread(r, "massive-poller");
            th.setDaemon(true);
            return th;
        });
        // First poll immediately so the cache has data right away.
        executor.scheduleWithFixedDelay(this::pollOnce, 0, Math.max(1000, pollInterval.toMillis()), TimeUnit.MILLISECONDS);
        log.info("Massive poller started: {} tickers, {} interval", tickers.size(), pollInterval);
    }

    @Override
    public synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        log.info("Massive poller stopped");
    }

    @Override
    public void addTicker(String ticker) {
        String t = ticker.trim().toUpperCase();
        if (!tickers.contains(t)) {
            tickers.add(t);
        }
    }

    @Override
    public void removeTicker(String ticker) {
        String t = ticker.trim().toUpperCase();
        tickers.remove(t);
        cache.remove(t);
    }

    @Override
    public List<String> getTickers() {
        return List.copyOf(tickers);
    }

    @Override
    public String name() {
        return "massive";
    }

    /** One poll cycle: fetch snapshots, update cache. Never throws (errors are logged and retried next cycle). */
    public void pollOnce() {
        if (tickers.isEmpty()) {
            return;
        }
        try {
            String body = client.get()
                    .uri(uri -> uri.path(SNAPSHOT_PATH).queryParam("tickers", String.join(",", tickers)).build())
                    .retrieve()
                    .body(String.class);
            List<Snapshot> snaps = parseSnapshots(body);
            for (Snapshot s : snaps) {
                if (tickers.contains(s.ticker())) {
                    cache.update(s.ticker(), s.price(), s.timestamp());
                }
            }
            log.debug("Massive poll: updated {}/{} tickers", snaps.size(), tickers.size());
        } catch (Exception e) {
            // 401 bad key, 429 rate limit, network errors... retry next interval.
            log.error("Massive poll failed: {}", e.toString());
        }
    }

    /**
     * Parses a snapshot response. Supports the raw REST shape
     * ({@code tickers[].lastTrade.p / .t}, falling back to {@code min.c}, {@code day.c}, {@code prevDay.c})
     * and the snake_case shape ({@code last_trade.price / .timestamp}). Malformed entries are skipped.
     */
    public List<Snapshot> parseSnapshots(String json) {
        List<Snapshot> out = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return out;
        }
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (Exception e) {
            log.warn("Unparseable Massive response: {}", e.getMessage());
            return out;
        }
        JsonNode list = root.isArray() ? root : root.path("tickers");
        if (!list.isArray()) {
            return out;
        }
        for (JsonNode n : list) {
            String ticker = n.path("ticker").asText(null);
            if (ticker == null || ticker.isBlank()) {
                continue;
            }
            JsonNode lt = n.has("lastTrade") ? n.path("lastTrade") : n.path("last_trade");
            double price = firstPositive(lt.path("p"), lt.path("price"), n.path("min").path("c"),
                    n.path("day").path("c"), n.path("day").path("close"), n.path("prevDay").path("c"));
            if (!(price > 0)) {
                log.warn("Skipping snapshot for {}: no price", ticker);
                continue;
            }
            JsonNode ts = lt.has("t") ? lt.path("t") : lt.path("timestamp");
            Instant when = ts.isNumber() ? toInstant(ts.asLong()) : Instant.now();
            out.add(new Snapshot(ticker.toUpperCase(), price, when));
        }
        return out;
    }

    private static double firstPositive(JsonNode... nodes) {
        for (JsonNode n : nodes) {
            if (n != null && n.isNumber() && n.asDouble() > 0) {
                return n.asDouble();
            }
        }
        return Double.NaN;
    }

    /** Massive timestamps may be in ns, us, ms or s; detect by magnitude. */
    static Instant toInstant(long v) {
        if (v <= 0) {
            return Instant.now();
        }
        if (v > 100_000_000_000_000_000L) {
            return Instant.ofEpochSecond(0, v);
        }
        if (v > 100_000_000_000_000L) {
            return Instant.ofEpochSecond(0, v * 1000);
        }
        if (v > 100_000_000_000L) {
            return Instant.ofEpochMilli(v);
        }
        return Instant.ofEpochSecond(v);
    }
}
