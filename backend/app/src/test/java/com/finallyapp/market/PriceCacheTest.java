package com.finallyapp.market;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class PriceCacheTest {

    private final PriceCache cache = new PriceCache();

    @Test
    void firstUpdateIsFlatAndSetsSessionOpen() {
        PriceTick t = cache.update("AAPL", 190.0);
        assertThat(t.price()).isEqualByComparingTo("190.00");
        assertThat(t.previousPrice()).isEqualByComparingTo("190.00");
        assertThat(t.sessionOpenPrice()).isEqualByComparingTo("190.00");
        assertThat(t.direction()).isEqualTo("flat");
    }

    @Test
    void tracksPreviousPriceAndDirection() {
        cache.update("AAPL", 190.0);
        PriceTick up = cache.update("AAPL", 191.5);
        assertThat(up.previousPrice()).isEqualByComparingTo("190.00");
        assertThat(up.direction()).isEqualTo("up");
        assertThat(up.change()).isEqualByComparingTo("1.50");
        PriceTick down = cache.update("AAPL", 189.0);
        assertThat(down.previousPrice()).isEqualByComparingTo("191.50");
        assertThat(down.direction()).isEqualTo("down");
        assertThat(down.sessionOpenPrice()).isEqualByComparingTo("190.00");
        assertThat(down.changePercent()).isEqualByComparingTo(new BigDecimal("-0.5263"));
    }

    @Test
    void roundsToCents() {
        assertThat(cache.update("X", 100.12345).price()).isEqualByComparingTo("100.12");
        assertThat(cache.update("X", 100.125).price()).isEqualByComparingTo("100.13");
    }

    @Test
    void keepsTimestamp() {
        Instant ts = Instant.parse("2026-11-02T10:00:00Z");
        assertThat(cache.update("AAPL", 1.0, ts).timestamp()).isEqualTo(ts);
    }

    @Test
    void getRemoveContainsAndVersion() {
        long v0 = cache.version();
        cache.update("AAPL", 190.0);
        cache.update("MSFT", 420.0);
        assertThat(cache.version()).isEqualTo(v0 + 2);
        assertThat(cache.price("AAPL")).isEqualByComparingTo("190");
        assertThat(cache.all()).containsOnlyKeys("AAPL", "MSFT");
        assertThat(cache.size()).isEqualTo(2);
        cache.remove("AAPL");
        assertThat(cache.contains("AAPL")).isFalse();
        assertThat(cache.get("AAPL")).isEmpty();
        assertThat(cache.price("AAPL")).isNull();
        // re-adding starts a fresh session-open price
        assertThat(cache.update("AAPL", 200.0).sessionOpenPrice()).isEqualByComparingTo("200");
    }

    @Test
    void notifiesListenersAndIsolatesFailures() {
        List<PriceTick> seen = new ArrayList<>();
        cache.addListener(t -> {
            throw new IllegalStateException("boom");
        });
        cache.addListener(seen::add);
        cache.update("AAPL", 190.0);
        cache.update("AAPL", 191.0);
        assertThat(seen).hasSize(2);
        assertThat(seen.get(1).previousPrice()).isEqualByComparingTo("190");
    }

    @Test
    void concurrentWritersDoNotLoseTickers() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        for (int i = 0; i < 8; i++) {
            int n = i;
            pool.submit(() -> {
                for (int k = 0; k < 1000; k++) {
                    cache.update("T" + n, 100 + k);
                }
            });
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        assertThat(cache.size()).isEqualTo(8);
        assertThat(cache.price("T3")).isEqualByComparingTo("1099");
    }
}
