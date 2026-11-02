package com.finallyapp.market;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finallyapp.config.FinallyProperties;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class SimulatorMarketDataSourceTest {

    private final PriceCache cache = new PriceCache();
    private final SimulatorMarketDataSource source =
            new SimulatorMarketDataSource(cache, Duration.ofHours(1), 0.0, new Random(1));

    @AfterEach
    void stop() {
        source.stop();
        source.stop(); // idempotent
    }

    @Test
    void startSeedsCacheImmediately() {
        source.start(List.of("AAPL", "GOOGL"));
        assertThat(cache.all()).containsOnlyKeys("AAPL", "GOOGL");
        assertThat(cache.price("AAPL")).isEqualByComparingTo("190.00");
        assertThat(source.getTickers()).containsExactly("AAPL", "GOOGL");
        assertThat(source.name()).isEqualTo("simulator");
    }

    @Test
    void tickWritesNewPrices() {
        source.start(List.of("AAPL"));
        long v = cache.version();
        source.tick();
        assertThat(cache.version()).isGreaterThan(v);
        assertThat(cache.get("AAPL")).isPresent();
    }

    @Test
    void addAndRemoveTicker() {
        source.start(List.of("AAPL"));
        source.addTicker("TSLA");
        assertThat(cache.price("TSLA")).isEqualByComparingTo("250.00");
        assertThat(source.getTickers()).contains("TSLA");
        source.removeTicker("TSLA");
        assertThat(cache.contains("TSLA")).isFalse();
        assertThat(source.getTickers()).doesNotContain("TSLA");
    }

    @Test
    void backgroundLoopRuns() throws Exception {
        SimulatorMarketDataSource fast = new SimulatorMarketDataSource(cache, Duration.ofMillis(20), 0.0);
        try {
            fast.start(List.of("AAPL"));
            long v = cache.version();
            Thread.sleep(300);
            assertThat(cache.version()).isGreaterThan(v + 3);
        } finally {
            fast.stop();
        }
    }

    @Test
    void factoryChoosesSourceByMassiveKey() {
        RestClient.Builder b = RestClient.builder();
        ObjectMapper m = new ObjectMapper();
        var sim = new FinallyProperties.Market("", "https://api.massive.com", Duration.ofSeconds(15), Duration.ofMillis(500), 0.001, "", "https://finnhub.io/api/v1", Duration.ofMillis(1100));
        var blank = new FinallyProperties.Market("   ", "https://api.massive.com", Duration.ofSeconds(15), Duration.ofMillis(500), 0.001, "", "https://finnhub.io/api/v1", Duration.ofMillis(1100));
        var massive = new FinallyProperties.Market("key", "https://api.massive.com", Duration.ofSeconds(15), Duration.ofMillis(500), 0.001, "", "https://finnhub.io/api/v1", Duration.ofMillis(1100));
        assertThat(MarketDataConfig.create(sim, cache, b, m)).isInstanceOf(SimulatorMarketDataSource.class);
        assertThat(MarketDataConfig.create(blank, cache, b, m)).isInstanceOf(SimulatorMarketDataSource.class);
        assertThat(MarketDataConfig.create(massive, cache, b, m)).isInstanceOf(MassiveMarketDataSource.class);
    }
}
