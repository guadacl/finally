package com.finallyapp.market;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * The trading day's high and low per ticker (US market day, New York time).
 *
 * <p>Every price tick widens the range, so it works with any market data source (simulator: since the server
 * started). A source that knows the real day range (Finnhub's quote {@code h}/{@code l}) reports it via
 * {@link #report}, and that range is merged in. A new trading day starts a fresh range.
 */
@Component
public class DayRangeCache implements PriceListener {

    static final ZoneId MARKET_ZONE = ZoneId.of("America/New_York");

    public record DayRange(BigDecimal high, BigDecimal low, LocalDate day) {
    }

    private final ConcurrentHashMap<String, DayRange> ranges = new ConcurrentHashMap<>();
    private final PriceCache cache;

    public DayRangeCache(PriceCache cache) {
        this.cache = cache;
    }

    @PostConstruct
    void register() {
        cache.addListener(this);
    }

    @PreDestroy
    void unregister() {
        cache.removeListener(this);
    }

    @Override
    public void onPrice(PriceTick tick) {
        merge(tick.ticker(), tick.price(), tick.price(), tick.timestamp());
    }

    /** A source's authoritative day high/low (either may be null or non-positive, then it is ignored). */
    public void report(String ticker, BigDecimal high, BigDecimal low, Instant asOf) {
        BigDecimal h = positive(high);
        BigDecimal l = positive(low);
        if (h == null && l == null) {
            return;
        }
        merge(ticker, h != null ? h : l, l != null ? l : h, asOf);
    }

    public Optional<DayRange> get(String ticker) {
        return Optional.ofNullable(ranges.get(ticker.toUpperCase()));
    }

    void merge(String ticker, BigDecimal high, BigDecimal low, Instant asOf) {
        if (high == null || low == null) {
            return;
        }
        LocalDate day = (asOf == null ? Instant.now() : asOf).atZone(MARKET_ZONE).toLocalDate();
        ranges.compute(ticker.toUpperCase(), (k, cur) -> {
            if (cur == null || day.isAfter(cur.day())) {
                return new DayRange(high.max(low), low.min(high), day);
            }
            if (day.isBefore(cur.day())) {
                return cur; // late data for an older day
            }
            return new DayRange(cur.high().max(high), cur.low().min(low), day);
        });
    }

    private static BigDecimal positive(BigDecimal v) {
        return v != null && v.signum() > 0 ? v : null;
    }
}
