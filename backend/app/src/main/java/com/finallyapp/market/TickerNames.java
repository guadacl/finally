package com.finallyapp.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finallyapp.config.FinallyProperties;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Company name of a ticker ("AAPL" → "Apple Inc."), shown next to the symbol in the Alerts page tables.
 *
 * <p>Common tickers are built in. Any other ticker is looked up once in the background, from Massive
 * ({@code /v3/reference/tickers/{ticker}}) when a {@code MASSIVE_API_KEY} is set, otherwise from Finnhub
 * ({@code /stock/profile2}) when a {@code FINNHUB_API_KEY} is set, and cached for the life of the process.
 * {@link #get} never blocks: until the lookup finishes (or without any key) the name is simply unknown.
 */
@Component
public class TickerNames {

    private static final Logger log = LoggerFactory.getLogger(TickerNames.class);
    static final String MASSIVE_PATH = "/v3/reference/tickers/{ticker}";
    static final String FINNHUB_PATH = "/stock/profile2";

    static final Map<String, String> BUILT_IN = Map.ofEntries(
            Map.entry("AAPL", "Apple Inc."),
            Map.entry("GOOGL", "Alphabet Inc. (Class A)"),
            Map.entry("GOOG", "Alphabet Inc. (Class C)"),
            Map.entry("MSFT", "Microsoft Corporation"),
            Map.entry("AMZN", "Amazon.com, Inc."),
            Map.entry("TSLA", "Tesla, Inc."),
            Map.entry("NVDA", "NVIDIA Corporation"),
            Map.entry("META", "Meta Platforms, Inc."),
            Map.entry("JPM", "JPMorgan Chase & Co."),
            Map.entry("V", "Visa Inc."),
            Map.entry("NFLX", "Netflix, Inc."),
            Map.entry("AMD", "Advanced Micro Devices, Inc."),
            Map.entry("INTC", "Intel Corporation"),
            Map.entry("ORCL", "Oracle Corporation"),
            Map.entry("CRM", "Salesforce, Inc."),
            Map.entry("ADBE", "Adobe Inc."),
            Map.entry("AVGO", "Broadcom Inc."),
            Map.entry("QCOM", "Qualcomm Incorporated"),
            Map.entry("IBM", "International Business Machines Corporation"),
            Map.entry("CSCO", "Cisco Systems, Inc."),
            Map.entry("PYPL", "PayPal Holdings, Inc."),
            Map.entry("MA", "Mastercard Incorporated"),
            Map.entry("BAC", "Bank of America Corporation"),
            Map.entry("WFC", "Wells Fargo & Company"),
            Map.entry("GS", "The Goldman Sachs Group, Inc."),
            Map.entry("MS", "Morgan Stanley"),
            Map.entry("BRK.B", "Berkshire Hathaway Inc. (Class B)"),
            Map.entry("JNJ", "Johnson & Johnson"),
            Map.entry("PFE", "Pfizer Inc."),
            Map.entry("UNH", "UnitedHealth Group Incorporated"),
            Map.entry("LLY", "Eli Lilly and Company"),
            Map.entry("WMT", "Walmart Inc."),
            Map.entry("COST", "Costco Wholesale Corporation"),
            Map.entry("KO", "The Coca-Cola Company"),
            Map.entry("PEP", "PepsiCo, Inc."),
            Map.entry("MCD", "McDonald's Corporation"),
            Map.entry("NKE", "NIKE, Inc."),
            Map.entry("DIS", "The Walt Disney Company"),
            Map.entry("XOM", "Exxon Mobil Corporation"),
            Map.entry("CVX", "Chevron Corporation"),
            Map.entry("BA", "The Boeing Company"),
            Map.entry("UBER", "Uber Technologies, Inc."),
            Map.entry("PLTR", "Palantir Technologies Inc."),
            Map.entry("SPY", "SPDR S&P 500 ETF Trust"),
            Map.entry("QQQ", "Invesco QQQ Trust"));

    private final RestClient massive;
    private final RestClient finnhub;
    private final String finnhubKey;
    private final ObjectMapper mapper;
    private final ExecutorService executor;

    private final Map<String, String> names = new ConcurrentHashMap<>(BUILT_IN);
    /** Lookups started (finished or not), so each ticker costs at most one API call per process. */
    private final Map<String, CompletableFuture<Void>> lookups = new ConcurrentHashMap<>();

    @Autowired
    public TickerNames(FinallyProperties props, RestClient.Builder builder, ObjectMapper mapper) {
        this(props.market().massiveApiKey(), props.market().massiveBaseUrl(),
                props.market().finnhubApiKey(), props.market().finnhubBaseUrl(), builder, mapper,
                Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "ticker-names");
                    t.setDaemon(true);
                    return t;
                }));
    }

    TickerNames(String massiveKey, String massiveBaseUrl, String finnhubKey, String finnhubBaseUrl,
                RestClient.Builder builder, ObjectMapper mapper, ExecutorService executor) {
        this.mapper = mapper;
        this.executor = executor;
        this.massive = isSet(massiveKey)
                ? builder.clone().baseUrl(massiveBaseUrl).defaultHeader("Authorization", "Bearer " + massiveKey.trim()).build()
                : null;
        this.finnhubKey = isSet(finnhubKey) ? finnhubKey.trim() : null;
        this.finnhub = this.finnhubKey != null ? builder.clone().baseUrl(finnhubBaseUrl).build() : null;
    }

    /** The cached name, or empty while unknown (a background lookup is started the first time). */
    public Optional<String> get(String ticker) {
        if (ticker == null || ticker.isBlank()) {
            return Optional.empty();
        }
        String t = normalize(ticker);
        String name = names.get(t);
        if (name == null) {
            start(t);
        }
        return Optional.ofNullable(name);
    }

    /**
     * Like {@link #get} but waits up to {@code timeout} for a lookup in progress, e.g. when a ticker is just
     * added to the watchlist so its row shows the name right away. Still empty when unknown after the wait.
     */
    public Optional<String> await(String ticker, Duration timeout) {
        Optional<String> now = get(ticker);
        if (now.isPresent()) {
            return now;
        }
        String t = normalize(ticker);
        CompletableFuture<Void> f = lookups.get(t);
        if (f != null) {
            try {
                f.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                // timeout or failure: the name stays unknown for now
            }
        }
        return Optional.ofNullable(names.get(t));
    }

    private void start(String t) {
        if (massive == null && finnhub == null) {
            return;
        }
        lookups.computeIfAbsent(t, k -> {
            try {
                return CompletableFuture.runAsync(() -> lookup(k), executor);
            } catch (RejectedExecutionException e) {
                return CompletableFuture.completedFuture(null); // shutting down
            }
        });
    }

    private static String normalize(String ticker) {
        return ticker == null ? "" : ticker.trim().toUpperCase(Locale.ROOT);
    }

    void lookup(String ticker) {
        try {
            String name = massive != null ? fromMassive(ticker) : null;
            if (name == null && finnhub != null) {
                name = fromFinnhub(ticker);
            }
            if (name != null) {
                names.put(ticker, name);
            }
        } catch (Exception e) {
            log.debug("Could not look up the name of {}: {}", ticker, e.toString());
        }
    }

    private String fromMassive(String ticker) {
        try {
            String body = massive.get().uri(MASSIVE_PATH, ticker).retrieve().body(String.class);
            return text(body, "results", "name");
        } catch (Exception e) {
            log.debug("Massive name lookup failed for {}: {}", ticker, e.toString());
            return null;
        }
    }

    private String fromFinnhub(String ticker) {
        String body = finnhub.get()
                .uri(uri -> uri.path(FINNHUB_PATH).queryParam("symbol", ticker).queryParam("token", finnhubKey).build())
                .retrieve().body(String.class);
        return text(body, "name");
    }

    /** Reads a non-blank string at the given JSON path, or null. */
    String text(String json, String... path) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode n = mapper.readTree(json);
            for (String p : path) {
                n = n.path(p);
            }
            String s = n.isTextual() ? n.asText().trim() : null;
            return s == null || s.isEmpty() ? null : s;
        } catch (Exception e) {
            return null;
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    private static boolean isSet(String s) {
        return s != null && !s.isBlank();
    }
}
