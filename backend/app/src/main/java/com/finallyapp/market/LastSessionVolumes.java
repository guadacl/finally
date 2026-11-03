package com.finallyapp.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finallyapp.config.FinallyProperties;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Trading volume of the last completed US market session, per ticker.
 *
 * <p>Live intraday volume isn't available on the free Finnhub or Massive plans, so this uses Massive's free
 * end-of-day data: one "grouped daily" call returns every US stock's volume for a date. It is fetched once per
 * new session (a few calls a day at most, well within the free 5 calls/minute) and cached. Without a
 * {@code MASSIVE_API_KEY} this is disabled and volumes are simply unknown.
 */
@Component
public class LastSessionVolumes {

    private static final Logger log = LoggerFactory.getLogger(LastSessionVolumes.class);
    static final String GROUPED_PATH = "/v2/aggs/grouped/locale/us/market/stocks/{date}";
    private static final int MAX_LOOKBACK_DAYS = 7;

    public record SessionVolume(long volume, LocalDate session) {
    }

    private final RestClient client;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final boolean enabled;

    private volatile Map<String, Long> volumes = Map.of();
    private volatile LocalDate session;

    @org.springframework.beans.factory.annotation.Autowired
    public LastSessionVolumes(FinallyProperties props, RestClient.Builder builder, ObjectMapper mapper) {
        this(props.market().massiveApiKey(), props.market().massiveBaseUrl(), builder, mapper, Clock.systemUTC());
    }

    LastSessionVolumes(String apiKey, String baseUrl, RestClient.Builder builder, ObjectMapper mapper, Clock clock) {
        this.enabled = apiKey != null && !apiKey.isBlank();
        this.mapper = mapper;
        this.clock = clock;
        this.client = builder.clone().baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + (enabled ? apiKey.trim() : ""))
                .build();
    }

    public Optional<SessionVolume> get(String ticker) {
        Long v = volumes.get(ticker.toUpperCase());
        LocalDate s = session;
        return v == null || s == null ? Optional.empty() : Optional.of(new SessionVolume(v, s));
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Checks every hour whether a newer completed session exists; costs no API call when it doesn't. */
    @Scheduled(initialDelayString = "${finally.market.volume-initial-delay-ms:10000}",
            fixedDelayString = "${finally.market.volume-refresh-ms:3600000}")
    public void refresh() {
        if (!enabled) {
            return;
        }
        LocalDate candidate = previousWeekday(LocalDate.now(clock.withZone(DayRangeCache.MARKET_ZONE)));
        if (candidate.equals(session)) {
            return;
        }
        for (int i = 0; i < MAX_LOOKBACK_DAYS; i++) {
            try {
                Map<String, Long> loaded = fetch(candidate);
                if (!loaded.isEmpty()) {
                    volumes = loaded;
                    session = candidate;
                    log.info("Loaded last-session volumes for {} tickers (session {})", loaded.size(), candidate);
                    return;
                }
                if (candidate.equals(session)) {
                    return; // older than what we already have
                }
                candidate = previousWeekday(candidate); // holiday: no data, try the session before
            } catch (Exception e) {
                log.warn("Could not load last-session volumes for {}: {}", candidate, e.toString());
                return; // retry on the next schedule
            }
        }
    }

    Map<String, Long> fetch(LocalDate date) {
        String body = client.get()
                .uri(uri -> uri.path(GROUPED_PATH).queryParam("adjusted", "true").build(date.toString()))
                .retrieve().body(String.class);
        return parse(body);
    }

    /** Parses {@code {"results":[{"T":"AAPL","v":6.47e7,...},...]}}; malformed entries are skipped. */
    Map<String, Long> parse(String json) {
        Map<String, Long> out = new HashMap<>();
        if (json == null || json.isBlank()) {
            return out;
        }
        try {
            JsonNode results = mapper.readTree(json).path("results");
            if (results.isArray()) {
                for (JsonNode r : results) {
                    String t = r.path("T").asText(null);
                    JsonNode v = r.path("v");
                    if (t != null && !t.isBlank() && v.isNumber() && v.asDouble() >= 0) {
                        out.put(t.toUpperCase(), Math.round(v.asDouble()));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Unparseable grouped-daily response: {}", e.getMessage());
        }
        return out;
    }

    static LocalDate previousWeekday(LocalDate day) {
        LocalDate d = day.minusDays(1);
        while (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
            d = d.minusDays(1);
        }
        return d;
    }
}
