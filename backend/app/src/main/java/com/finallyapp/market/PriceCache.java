package com.finallyapp.market;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Thread-safe in-memory cache of the latest price per ticker.
 * Writers: the active {@link MarketDataSource}. Readers: SSE stream, portfolio, watchlist, kanban, chat.
 * Listeners (e.g. the kanban alert engine) are notified after each write.
 */
@Component
public class PriceCache {

    private static final Logger log = LoggerFactory.getLogger(PriceCache.class);

    private final ConcurrentHashMap<String, PriceTick> prices = new ConcurrentHashMap<>();
    private final List<PriceListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicLong version = new AtomicLong();

    /**
     * Records a new price (rounded to cents). The first update for a ticker has previousPrice == price
     * (direction "flat") and sets the session-open price.
     */
    public PriceTick update(String ticker, double price, Instant timestamp) {
        BigDecimal p = BigDecimal.valueOf(price).setScale(2, RoundingMode.HALF_UP);
        Instant ts = timestamp != null ? timestamp : Instant.now();
        PriceTick tick = prices.compute(ticker, (k, prev) -> prev == null
                ? new PriceTick(k, p, p, ts, p)
                : new PriceTick(k, p, prev.price(), ts, prev.sessionOpenPrice()));
        version.incrementAndGet();
        for (PriceListener l : listeners) {
            try {
                l.onPrice(tick);
            } catch (RuntimeException e) {
                log.warn("Price listener failed for {}: {}", ticker, e.toString());
            }
        }
        return tick;
    }

    public PriceTick update(String ticker, double price) {
        return update(ticker, price, Instant.now());
    }

    public Optional<PriceTick> get(String ticker) {
        return Optional.ofNullable(prices.get(ticker));
    }

    public BigDecimal price(String ticker) {
        PriceTick t = prices.get(ticker);
        return t == null ? null : t.price();
    }

    /** Snapshot copy, sorted by ticker. */
    public Map<String, PriceTick> all() {
        return new TreeMap<>(prices);
    }

    public void remove(String ticker) {
        prices.remove(ticker);
        version.incrementAndGet();
    }

    public boolean contains(String ticker) {
        return prices.containsKey(ticker);
    }

    public int size() {
        return prices.size();
    }

    public long version() {
        return version.get();
    }

    public void addListener(PriceListener listener) {
        listeners.add(listener);
    }

    public void removeListener(PriceListener listener) {
        listeners.remove(listener);
    }
}
