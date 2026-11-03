package com.finallyapp.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RsiTest {

    /**
     * StockCharts' RSI worked example (14 periods). Their table shows 70.53 / 66.32 because it rounds the
     * intermediate averages; computed without rounding the same data gives 70.46, then 66.25 after a close of 46.00.
     */
    private static final List<Double> EXAMPLE = List.of(44.34, 44.09, 44.15, 43.61, 44.33, 44.83, 45.10, 45.42,
            45.84, 46.08, 45.89, 46.03, 45.61, 46.28, 46.28);

    @Test
    void matchesTheReferenceExample() {
        assertThat(Rsi.wilder(EXAMPLE, 14).getAsDouble()).isCloseTo(70.46, within(0.01));
        List<Double> more = new ArrayList<>(EXAMPLE);
        more.add(46.00);
        assertThat(Rsi.wilder(more, 14).getAsDouble()).isCloseTo(66.25, within(0.01));
    }

    @Test
    void needsPeriodPlusOneCloses() {
        assertThat(Rsi.wilder(EXAMPLE.subList(0, 14), 14)).isEmpty();
        assertThat(Rsi.wilder(List.of(), 14)).isEmpty();
        assertThat(Rsi.wilder(null, 14)).isEmpty();
    }

    @Test
    void handlesOneSidedAndFlatSeries() {
        List<Double> up = new ArrayList<>();
        List<Double> down = new ArrayList<>();
        List<Double> flat = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            up.add(100.0 + i);
            down.add(100.0 - i);
            flat.add(100.0);
        }
        assertThat(Rsi.wilder(up, 14).getAsDouble()).isEqualTo(100.0);
        assertThat(Rsi.wilder(down, 14).getAsDouble()).isEqualTo(0.0);
        assertThat(Rsi.wilder(flat, 14).getAsDouble()).isEqualTo(50.0);
    }
}
