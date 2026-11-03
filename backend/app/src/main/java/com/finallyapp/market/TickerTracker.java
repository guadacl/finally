package com.finallyapp.market;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Keeps the market data source's ticker set equal to the watchlist ∪ kanban card ∪ open position tickers
 * of <em>all</em> users (prices are shared; each user only sees their own lists).
 * Starts the source once the application (and Flyway) is ready.
 */
@Component
public class TickerTracker {

    private static final Logger log = LoggerFactory.getLogger(TickerTracker.class);

    private final MarketDataSource source;
    private final JdbcClient jdbc;

    public TickerTracker(MarketDataSource source, JdbcClient jdbc) {
        this.source = source;
        this.jdbc = jdbc;
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

    /** Stop pricing the ticker if no watchlist entry, kanban card or position references it anymore. */
    public void untrackIfUnused(String ticker) {
        Integer refs = jdbc.sql("""
                        SELECT (SELECT count(*) FROM watchlist WHERE ticker = :t)
                             + (SELECT count(*) FROM kanban_cards WHERE ticker = :t)
                             + (SELECT count(*) FROM positions WHERE ticker = :t)
                        """)
                .param("t", ticker)
                .query(Integer.class).single();
        if (refs == null || refs == 0) {
            source.removeTicker(ticker);
            log.info("Stopped tracking {}", ticker);
        }
    }

    public boolean isTracked(String ticker) {
        return source.getTickers().contains(ticker);
    }

    List<String> referencedTickers() {
        return jdbc.sql("""
                        SELECT ticker FROM (
                          SELECT ticker, added_at AS ts, 0 AS src FROM watchlist
                          UNION ALL SELECT ticker, created_at, 1 FROM kanban_cards
                          UNION ALL SELECT ticker, updated_at, 2 FROM positions
                        ) x GROUP BY ticker ORDER BY min(src), min(ts), ticker
                        """)
                .query(String.class).list();
    }
}
