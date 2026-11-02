package com.finallyapp.market;

import java.util.Collection;
import java.util.List;

/**
 * Contract for market data providers. Implementations push price updates into the shared
 * {@link PriceCache} on their own schedule; downstream code reads only from the cache.
 */
public interface MarketDataSource {

    /** Begin producing prices for the given tickers (background thread). Call once. */
    void start(Collection<String> tickers);

    /** Stop producing prices. Safe to call multiple times. */
    void stop();

    /** Add a ticker to the active set (no-op if present). */
    void addTicker(String ticker);

    /** Remove a ticker from the active set and from the cache (no-op if absent). */
    void removeTicker(String ticker);

    List<String> getTickers();

    /** "simulator", "massive" or "finnhub". */
    String name();
}
