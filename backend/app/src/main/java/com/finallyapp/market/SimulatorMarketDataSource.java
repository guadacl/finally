package com.finallyapp.market;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** {@link MarketDataSource} backed by the GBM simulator, stepping every ~500 ms on a daemon thread. */
public class SimulatorMarketDataSource implements MarketDataSource {

    private static final Logger log = LoggerFactory.getLogger(SimulatorMarketDataSource.class);

    private final PriceCache cache;
    private final Duration interval;
    private final double eventProbability;
    private final Random random;
    private GbmSimulator sim;
    private ScheduledExecutorService executor;
    private ScheduledFuture<?> task;

    public SimulatorMarketDataSource(PriceCache cache, Duration interval, double eventProbability) {
        this(cache, interval, eventProbability, new Random());
    }

    public SimulatorMarketDataSource(PriceCache cache, Duration interval, double eventProbability, Random random) {
        this.cache = cache;
        this.interval = interval;
        this.eventProbability = eventProbability;
        this.random = random;
    }

    @Override
    public synchronized void start(Collection<String> tickers) {
        if (sim != null) {
            return;
        }
        sim = new GbmSimulator(List.copyOf(tickers), GbmSimulator.DEFAULT_DT, eventProbability, random);
        // Seed the cache so SSE has data immediately.
        for (String t : sim.getTickers()) {
            cache.update(t, sim.getPrice(t));
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread th = new Thread(r, "market-simulator");
            th.setDaemon(true);
            return th;
        });
        long ms = Math.max(10, interval.toMillis());
        task = executor.scheduleAtFixedRate(this::tick, ms, ms, TimeUnit.MILLISECONDS);
        log.info("Simulator started with {} tickers ({} ms ticks)", tickers.size(), ms);
    }

    /** One simulation step: advance prices and write them to the cache. */
    void tick() {
        try {
            Map<String, Double> prices;
            synchronized (this) {
                if (sim == null) {
                    return;
                }
                prices = sim.step();
            }
            prices.forEach(cache::update);
        } catch (RuntimeException e) {
            log.error("Simulator step failed", e);
        }
    }

    @Override
    public synchronized void stop() {
        if (task != null) {
            task.cancel(false);
            task = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        log.info("Simulator stopped");
    }

    @Override
    public void addTicker(String ticker) {
        Double price;
        synchronized (this) {
            if (sim == null) {
                return;
            }
            sim.addTicker(ticker);
            price = sim.getPrice(ticker);
        }
        if (price != null && !cache.contains(ticker)) {
            cache.update(ticker, price);
        }
    }

    @Override
    public void removeTicker(String ticker) {
        synchronized (this) {
            if (sim != null) {
                sim.removeTicker(ticker);
            }
        }
        cache.remove(ticker);
    }

    @Override
    public synchronized List<String> getTickers() {
        return sim == null ? List.of() : sim.getTickers();
    }

    @Override
    public String name() {
        return "simulator";
    }
}
