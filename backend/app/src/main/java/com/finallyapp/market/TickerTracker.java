package com.finallyapp.market;

import com.finallyapp.persistence.KanbanCardRepository;
import com.finallyapp.persistence.PositionRepository;
import com.finallyapp.persistence.PriceAlertRepository;
import com.finallyapp.persistence.TickerFirstSeen;
import com.finallyapp.persistence.WatchlistRepository;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Keeps the market data source's ticker set equal to the watchlist ∪ kanban card ∪ open position ∪ price alert tickers
 * of <em>all</em> users (prices are shared; each user only sees their own lists).
 * Starts the source once the application (and Flyway) is ready.
 */
@Component
public class TickerTracker {

    private static final Logger log = LoggerFactory.getLogger(TickerTracker.class);

    private final MarketDataSource source;
    private final WatchlistRepository watchlist;
    private final KanbanCardRepository cards;
    private final PositionRepository positions;
    private final PriceAlertRepository alerts;

    public TickerTracker(MarketDataSource source, WatchlistRepository watchlist, KanbanCardRepository cards,
                         PositionRepository positions, PriceAlertRepository alerts) {
        this.source = source;
        this.watchlist = watchlist;
        this.cards = cards;
        this.positions = positions;
        this.alerts = alerts;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(100)
    public void startSource() {
        Set<String> tickers = new LinkedHashSet<>();
        try {
            tickers.addAll(referencedTickers());
        } catch (RuntimeException e) {
            log.error("Could not load tracked tickers from the database: {}", e.toString());
        }
        source.start(tickers);
    }

    /** Ensure the ticker gets live prices (no-op if already tracked). */
    public void track(String ticker) {
        source.addTicker(ticker);
    }

    /** Stop pricing the ticker if no watchlist entry, kanban card, position or price alert references it anymore. */
    public void untrackIfUnused(String ticker) {
        long refs = watchlist.countByTicker(ticker) + cards.countByTicker(ticker)
                + positions.countByTicker(ticker) + alerts.countByTicker(ticker);
        if (refs == 0) {
            source.removeTicker(ticker);
            log.info("Stopped tracking {}", ticker);
        }
    }

    public boolean isTracked(String ticker) {
        return source.getTickers().contains(ticker);
    }

    /**
     * Every referenced ticker, ordered like the original union query: first by the earliest source that references
     * it (watchlist, kanban cards, positions, price alerts), then by first-seen time, then by name.
     */
    List<String> referencedTickers() {
        record Ref(String ticker, int src, OffsetDateTime ts) {
        }
        Map<String, Ref> first = new HashMap<>();
        List<List<TickerFirstSeen>> sources = List.of(watchlist.findTickersFirstSeen(), cards.findTickersFirstSeen(),
                positions.findTickersFirstSeen(), alerts.findTickersFirstSeen());
        for (int src = 0; src < sources.size(); src++) {
            for (TickerFirstSeen t : sources.get(src)) {
                Ref candidate = new Ref(t.ticker(), src, t.firstSeen());
                first.merge(t.ticker(), candidate, (a, b) -> new Ref(a.ticker(), Math.min(a.src(), b.src()),
                        a.ts() == null ? b.ts() : b.ts() == null ? a.ts() : a.ts().isBefore(b.ts()) ? a.ts() : b.ts()));
            }
        }
        return first.values().stream()
                .sorted(Comparator.comparingInt(Ref::src)
                        .thenComparing(Ref::ts, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Ref::ticker))
                .map(Ref::ticker)
                .toList();
    }
}
