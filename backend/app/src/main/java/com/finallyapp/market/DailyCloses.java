package com.finallyapp.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finallyapp.config.FinallyProperties;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Daily closing prices per ticker (Massive end-of-day aggregates, free tier) and the RSI computed from them.
 *
 * <p>Tickers are loaded on demand: {@link #rsi} marks a ticker as wanted, and a scheduled task loads one wanted
 * ticker per run (~3 calls/minute, inside the free 5 calls/minute shared with {@link LastSessionVolumes}).
 * Each ticker's history is fetched once per day and cached. Without a {@code MASSIVE_API_KEY} this is disabled
 * and RSI is simply unknown.
 */
@Component
public class DailyCloses {

    private static final Logger log = LoggerFactory.getLogger(DailyCloses.class);
    static final String AGGS_PATH = "/v2/aggs/ticker/{ticker}/range/1/day/{from}/{to}";
    /** Calendar days of history: ~100 sessions, enough for Wilder's smoothing to settle. */
    static final int LOOKBACK_DAYS = 150;
    private static final LocalTime MARKET_OPEN = LocalTime.of(9, 30);

    /** Closes of the sessions before {@code loadedOn}, oldest first. */
    private record Series(List<Double> closes, LocalDate loadedOn) {
    }

    private final RestClient client;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final boolean enabled;

    private final Map<String, Series> series = new ConcurrentHashMap<>();
    private final Set<String> wanted = ConcurrentHashMap.newKeySet();

    @org.springframework.beans.factory.annotation.Autowired
    public DailyCloses(FinallyProperties props, RestClient.Builder builder, ObjectMapper mapper) {
        this(props.market().massiveApiKey(), props.market().massiveBaseUrl(), builder, mapper, Clock.systemUTC());
    }

    DailyCloses(String apiKey, String baseUrl, RestClient.Builder builder, ObjectMapper mapper, Clock clock) {
        this.enabled = apiKey != null && !apiKey.isBlank();
        this.mapper = mapper;
        this.clock = clock;
        this.client = builder.clone().baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + (enabled ? apiKey.trim() : ""))
                .build();
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * RSI(14) of the daily closes; while today's session is running, {@code livePrice} counts as today's close so
     * the value moves intraday. Empty until the history is loaded (requested here) or without a Massive key.
     */
    public OptionalDouble rsi(String ticker, BigDecimal livePrice) {
        List<Double> closes = closes(ticker);
        if (closes.isEmpty()) {
            return OptionalDouble.empty();
        }
        if (livePrice != null && livePrice.signum() > 0 && sessionStartedToday()) {
            closes = new ArrayList<>(closes);
            closes.add(livePrice.doubleValue());
        }
        return Rsi.wilder(closes, Rsi.PERIOD);
    }

    /** Cached closes (oldest first, completed sessions only); asks for a (re)load when missing or from an earlier day. */
    List<Double> closes(String ticker) {
        if (!enabled || ticker == null) {
            return List.of();
        }
        String t = ticker.toUpperCase();
        Series s = series.get(t);
        if (s == null || !s.loadedOn().equals(today())) {
            wanted.add(t);
        }
        return s == null ? List.of() : s.closes();
    }

    /** Loads one wanted ticker per run, which keeps the call rate low. */
    @Scheduled(initialDelayString = "${finally.market.closes-initial-delay-ms:3000}",
            fixedDelayString = "${finally.market.closes-refresh-ms:20000}")
    public void loadNext() {
        if (!enabled) {
            return;
        }
        Iterator<String> it = wanted.iterator();
        if (!it.hasNext()) {
            return;
        }
        String ticker = it.next();
        wanted.remove(ticker); // re-requested by the next rsi() call if this load fails
        LocalDate today = today();
        try {
            List<Double> closes = fetch(ticker, today.minusDays(LOOKBACK_DAYS), today.minusDays(1));
            series.put(ticker, new Series(List.copyOf(closes), today));
            log.debug("Loaded {} daily closes for {}", closes.size(), ticker);
        } catch (Exception e) {
            log.warn("Could not load daily closes for {}: {}", ticker, e.toString());
        }
    }

    List<Double> fetch(String ticker, LocalDate from, LocalDate to) {
        String body = client.get()
                .uri(uri -> uri.path(AGGS_PATH)
                        .queryParam("adjusted", "true").queryParam("sort", "asc").queryParam("limit", "5000")
                        .build(ticker, from.toString(), to.toString()))
                .retrieve().body(String.class);
        return parse(body);
    }

    /** Parses {@code {"results":[{"c":325.13,"t":...},...]}} (ascending); entries without a positive close are skipped. */
    List<Double> parse(String json) {
        List<Double> out = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return out;
        }
        try {
            JsonNode results = mapper.readTree(json).path("results");
            if (results.isArray()) {
                for (JsonNode r : results) {
                    JsonNode c = r.path("c");
                    if (c.isNumber() && c.asDouble() > 0) {
                        out.add(c.asDouble());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Unparseable daily aggregates response: {}", e.getMessage());
        }
        return out;
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(DayRangeCache.MARKET_ZONE));
    }

    /** True on a weekday after 09:30 New York time (holidays are not detected; the live price then equals the last close). */
    boolean sessionStartedToday() {
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(DayRangeCache.MARKET_ZONE));
        DayOfWeek d = now.getDayOfWeek();
        return d != DayOfWeek.SATURDAY && d != DayOfWeek.SUNDAY && !now.toLocalTime().isBefore(MARKET_OPEN);
    }
}
