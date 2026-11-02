package com.finallyapp.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * {@link MarketDataSource} backed by the Finnhub REST API ({@code GET /quote?symbol=...}), which serves real-time
 * US stock quotes on the free tier (60 calls/min). Finnhub quotes one symbol per call, so tickers are polled
 * round-robin, one call every {@code callSpacing} (default 1.1 s, about 55 calls/min). With N tickers each one
 * refreshes roughly every N × callSpacing.
 */
public class FinnhubMarketDataSource implements MarketDataSource {

    private static final Logger log = LoggerFactory.getLogger(FinnhubMarketDataSource.class);
    static final String QUOTE_PATH = "/quote";
    static final Duration RATE_LIMIT_BACKOFF = Duration.ofSeconds(15);

    /** One parsed quote. */
    public record Quote(String ticker, double price, Instant timestamp) {
    }

    private final PriceCache cache;
    private final RestClient client;
    private final Duration callSpacing;
    private final ObjectMapper mapper;
    private final List<String> tickers = new CopyOnWriteArrayList<>();
    private final AtomicInteger cursor = new AtomicInteger();
    private volatile Instant pausedUntil = Instant.EPOCH;
    private ScheduledExecutorService executor;

    public FinnhubMarketDataSource(PriceCache cache, RestClient.Builder builder, String baseUrl, String apiKey,
                                   Duration callSpacing, ObjectMapper mapper) {
        this.cache = cache;
        this.callSpacing = callSpacing;
        this.mapper = mapper;
        this.client = builder.baseUrl(baseUrl)
                .defaultHeader("X-Finnhub-Token", apiKey)
                .build();
    }

    @Override
    public synchronized void start(Collection<String> initial) {
        if (executor != null) {
            return;
        }
        initial.forEach(this::addTicker);
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread th = new Thread(r, "finnhub-poller");
            th.setDaemon(true);
            return th;
        });
        executor.scheduleWithFixedDelay(this::pollNext, 0, Math.max(250, callSpacing.toMillis()), TimeUnit.MILLISECONDS);
        log.info("Finnhub poller started: {} tickers, one quote every {}", tickers.size(), callSpacing);
    }

    @Override
    public synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        log.info("Finnhub poller stopped");
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
        return "finnhub";
    }

    /** Quotes the next ticker in the rotation. Never throws (errors are logged; the rotation continues). */
    public void pollNext() {
        if (tickers.isEmpty() || Instant.now().isBefore(pausedUntil)) {
            return;
        }
        List<String> snapshot = List.copyOf(tickers);
        String ticker = snapshot.get(Math.floorMod(cursor.getAndIncrement(), snapshot.size()));
        try {
            String body = client.get()
                    .uri(uri -> uri.path(QUOTE_PATH).queryParam("symbol", ticker).build())
                    .retrieve()
                    .body(String.class);
            parseQuote(ticker, body).ifPresent(q -> {
                if (tickers.contains(q.ticker())) {
                    cache.update(q.ticker(), q.price(), q.timestamp());
                }
            });
        } catch (HttpClientErrorException.TooManyRequests e) {
            pausedUntil = Instant.now().plus(RATE_LIMIT_BACKOFF);
            log.warn("Finnhub rate limit hit; pausing {}", RATE_LIMIT_BACKOFF);
        } catch (Exception e) {
            // 401/403 bad key, network errors... keep rotating.
            log.error("Finnhub quote for {} failed: {}", ticker, e.toString());
        }
    }

    /**
     * Parses {@code {"c":current,"d":change,"dp":pct,"h":high,"l":low,"o":open,"pc":prevClose,"t":epochSeconds}}.
     * Finnhub answers unknown symbols with {@code c = 0}; those (and malformed bodies) yield empty.
     */
    public Optional<Quote> parseQuote(String ticker, String json) {
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode root = mapper.readTree(json);
            JsonNode c = root.path("c");
            if (!c.isNumber() || !(c.asDouble() > 0)) {
                log.warn("Finnhub returned no price for {} (unknown symbol?)", ticker);
                return Optional.empty();
            }
            JsonNode t = root.path("t");
            Instant when = t.isNumber() && t.asLong() > 0 ? Instant.ofEpochSecond(t.asLong()) : Instant.now();
            return Optional.of(new Quote(ticker.toUpperCase(), c.asDouble(), when));
        } catch (Exception e) {
            log.warn("Unparseable Finnhub response for {}: {}", ticker, e.getMessage());
            return Optional.empty();
        }
    }
}
