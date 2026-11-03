package com.finallyapp.market;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class DayRangeCacheTest {

    private static final Instant MON_10AM_NY = Instant.parse("2026-11-02T15:00:00Z");
    private static final Instant MON_3PM_NY = Instant.parse("2026-11-02T20:00:00Z");
    private static final Instant TUE_10AM_NY = Instant.parse("2026-11-03T15:00:00Z");

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    @Test
    void ticksWidenTheRangeAndANewDayResetsIt() {
        DayRangeCache days = new DayRangeCache(new PriceCache());
        days.merge("aapl", d("100"), d("100"), MON_10AM_NY);
        days.merge("AAPL", d("103"), d("103"), MON_3PM_NY);
        days.merge("AAPL", d("98.5"), d("98.5"), MON_3PM_NY);
        var mon = days.get("AAPL").orElseThrow();
        assertThat(mon.high()).isEqualByComparingTo("103");
        assertThat(mon.low()).isEqualByComparingTo("98.5");

        days.merge("AAPL", d("101"), d("101"), TUE_10AM_NY);
        var tue = days.get("AAPL").orElseThrow();
        assertThat(tue.high()).isEqualByComparingTo("101");
        assertThat(tue.low()).isEqualByComparingTo("101");

        days.merge("AAPL", d("50"), d("50"), MON_3PM_NY); // late data for Monday is ignored
        assertThat(days.get("AAPL").orElseThrow().low()).isEqualByComparingTo("101");
    }

    @Test
    void reportedSourceRangeIsMergedWithTicks() {
        DayRangeCache days = new DayRangeCache(new PriceCache());
        days.merge("AAPL", d("100"), d("100"), MON_10AM_NY);
        days.report("AAPL", d("105"), d("95"), MON_10AM_NY);
        var r = days.get("AAPL").orElseThrow();
        assertThat(r.high()).isEqualByComparingTo("105");
        assertThat(r.low()).isEqualByComparingTo("95");

        days.report("AAPL", null, BigDecimal.ZERO, MON_10AM_NY); // nothing usable
        assertThat(days.get("AAPL").orElseThrow().low()).isEqualByComparingTo("95");
    }

    @Test
    void listensToThePriceCache() {
        PriceCache cache = new PriceCache();
        DayRangeCache days = new DayRangeCache(cache);
        days.register();
        cache.update("MSFT", 500.0, MON_10AM_NY);
        cache.update("MSFT", 510.0, MON_3PM_NY);
        assertThat(days.get("MSFT").orElseThrow().high()).isEqualByComparingTo("510");
        assertThat(days.get("MSFT").orElseThrow().low()).isEqualByComparingTo("500");
    }
}
