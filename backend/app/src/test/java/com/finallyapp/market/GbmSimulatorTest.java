package com.finallyapp.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class GbmSimulatorTest {

    private static final List<String> DEFAULT = List.of("AAPL", "GOOGL", "MSFT", "AMZN", "TSLA", "NVDA", "META", "JPM", "V", "NFLX");

    @Test
    void startsAtSeedPrices() {
        GbmSimulator sim = new GbmSimulator(DEFAULT, GbmSimulator.DEFAULT_DT, 0, new Random(1));
        assertThat(sim.getPrice("AAPL")).isEqualTo(190.0);
        assertThat(sim.getPrice("NVDA")).isEqualTo(800.0);
        assertThat(sim.getTickers()).containsExactlyElementsOf(DEFAULT);
    }

    @Test
    void stepProducesPositiveCentRoundedPricesForEveryTicker() {
        GbmSimulator sim = new GbmSimulator(DEFAULT, GbmSimulator.DEFAULT_DT, 0.001, new Random(2));
        for (int i = 0; i < 1000; i++) {
            Map<String, Double> prices = sim.step();
            assertThat(prices).hasSize(DEFAULT.size());
            prices.forEach((t, p) -> {
                assertThat(p).isPositive();
                assertThat(Math.abs(p * 100 - Math.round(p * 100))).isLessThan(1e-6);
            });
        }
    }

    @Test
    void tinyDtGivesSubPercentMovesPerTick() {
        GbmSimulator sim = new GbmSimulator(List.of("AAPL"), GbmSimulator.DEFAULT_DT, 0, new Random(3));
        double prev = sim.getPrice("AAPL");
        for (int i = 0; i < 500; i++) {
            sim.step();
            double p = sim.getPrice("AAPL");
            assertThat(Math.abs(p / prev - 1)).isLessThan(0.01);
            prev = p;
        }
    }

    @Test
    void logReturnsMatchGbmDriftAndVolatility() {
        double dt = 1.0 / 252;
        GbmSimulator sim = new GbmSimulator(List.of("AAPL"), dt, 0, new Random(4));
        SeedPrices.Params p = SeedPrices.paramsFor("AAPL");
        int n = 40_000;
        double[] r = new double[n];
        double prev = sim.getPrice("AAPL");
        for (int i = 0; i < n; i++) {
            sim.step();
            double cur = sim.getPrice("AAPL");
            r[i] = Math.log(cur / prev);
            prev = cur;
        }
        double mean = mean(r);
        double sd = Math.sqrt(variance(r, mean));
        assertThat(sd).isCloseTo(p.sigma() * Math.sqrt(dt), within(p.sigma() * Math.sqrt(dt) * 0.03));
        double expectedMean = (p.mu() - 0.5 * p.sigma() * p.sigma()) * dt;
        // standard error of the mean = sd / sqrt(n); allow 4 SE
        assertThat(mean).isCloseTo(expectedMean, within(4 * sd / Math.sqrt(n)));
    }

    @Test
    void techStocksAreCorrelatedAndTslaLess() {
        double dt = 1.0 / 252;
        GbmSimulator sim = new GbmSimulator(List.of("AAPL", "MSFT", "TSLA", "JPM", "V"), dt, 0, new Random(5));
        int n = 20_000;
        Map<String, double[]> returns = Map.of("AAPL", new double[n], "MSFT", new double[n], "TSLA", new double[n],
                "JPM", new double[n], "V", new double[n]);
        Map<String, Double> prev = new java.util.HashMap<>();
        returns.keySet().forEach(t -> prev.put(t, sim.getPrice(t)));
        for (int i = 0; i < n; i++) {
            sim.step();
            for (String t : returns.keySet()) {
                double cur = sim.getPrice(t);
                returns.get(t)[i] = Math.log(cur / prev.get(t));
                prev.put(t, cur);
            }
        }
        assertThat(corr(returns.get("AAPL"), returns.get("MSFT"))).isCloseTo(0.6, within(0.04));
        assertThat(corr(returns.get("JPM"), returns.get("V"))).isCloseTo(0.5, within(0.04));
        assertThat(corr(returns.get("AAPL"), returns.get("TSLA"))).isCloseTo(0.3, within(0.04));
        assertThat(corr(returns.get("AAPL"), returns.get("JPM"))).isCloseTo(0.3, within(0.04));
    }

    @Test
    void pairwiseCorrelationRules() {
        assertThat(GbmSimulator.pairwiseCorrelation("AAPL", "GOOGL")).isEqualTo(0.6);
        assertThat(GbmSimulator.pairwiseCorrelation("JPM", "V")).isEqualTo(0.5);
        assertThat(GbmSimulator.pairwiseCorrelation("TSLA", "AAPL")).isEqualTo(0.3);
        assertThat(GbmSimulator.pairwiseCorrelation("AAPL", "JPM")).isEqualTo(0.3);
        assertThat(GbmSimulator.pairwiseCorrelation("PYPL", "ZZZ")).isEqualTo(0.3);
    }

    @Test
    void choleskyFactorReproducesCorrelationMatrix() {
        GbmSimulator sim = new GbmSimulator(DEFAULT, GbmSimulator.DEFAULT_DT, 0, new Random(6));
        double[][] l = sim.choleskyFactor();
        int n = DEFAULT.size();
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                double s = 0;
                for (int k = 0; k < n; k++) {
                    s += l[i][k] * l[j][k];
                }
                double expected = i == j ? 1.0 : GbmSimulator.pairwiseCorrelation(DEFAULT.get(i), DEFAULT.get(j));
                assertThat(s).isCloseTo(expected, within(1e-9));
            }
        }
    }

    @Test
    void randomEventsMoveTwoToFivePercent() {
        // event probability 1 and dt ~ 0: every tick is a pure 2-5% shock
        GbmSimulator sim = new GbmSimulator(List.of("AAPL"), 1e-15, 1.0, new Random(7));
        int up = 0;
        int down = 0;
        for (int i = 0; i < 300; i++) {
            double before = sim.getPrice("AAPL");
            sim.step();
            double move = sim.getPrice("AAPL") / before - 1;
            assertThat(Math.abs(move)).isBetween(0.0199, 0.0501);
            if (move > 0) {
                up++;
            } else {
                down++;
            }
        }
        assertThat(up).isGreaterThan(50);
        assertThat(down).isGreaterThan(50);
    }

    @Test
    void noEventsWhenProbabilityZero() {
        GbmSimulator sim = new GbmSimulator(List.of("AAPL"), 1e-15, 0.0, new Random(8));
        sim.step();
        assertThat(sim.getPrice("AAPL")).isCloseTo(190.0, within(1e-3));
    }

    @Test
    void addAndRemoveTickers() {
        GbmSimulator sim = new GbmSimulator(List.of("AAPL"), GbmSimulator.DEFAULT_DT, 0, new Random(9));
        assertThat(sim.choleskyFactor()).isNull();
        sim.addTicker("PYPL");
        sim.addTicker("PYPL");
        assertThat(sim.getTickers()).containsExactly("AAPL", "PYPL");
        assertThat(sim.getPrice("PYPL")).isBetween(50.0, 300.0);
        assertThat(sim.choleskyFactor()).isNotNull();
        assertThat(sim.step()).containsOnlyKeys("AAPL", "PYPL");
        sim.removeTicker("AAPL");
        sim.removeTicker("NOPE");
        assertThat(sim.getTickers()).containsExactly("PYPL");
        assertThat(sim.getPrice("AAPL")).isNull();
        assertThat(sim.step()).containsOnlyKeys("PYPL");
    }

    @Test
    void emptySimulatorStepsToEmptyMap() {
        assertThat(new GbmSimulator(new ArrayList<>()).step()).isEmpty();
    }

    private static double mean(double[] a) {
        double s = 0;
        for (double v : a) {
            s += v;
        }
        return s / a.length;
    }

    private static double variance(double[] a, double m) {
        double s = 0;
        for (double v : a) {
            s += (v - m) * (v - m);
        }
        return s / (a.length - 1);
    }

    private static double corr(double[] a, double[] b) {
        double ma = mean(a);
        double mb = mean(b);
        double cov = 0;
        double va = 0;
        double vb = 0;
        for (int i = 0; i < a.length; i++) {
            cov += (a[i] - ma) * (b[i] - mb);
            va += (a[i] - ma) * (a[i] - ma);
            vb += (b[i] - mb) * (b[i] - mb);
        }
        return cov / Math.sqrt(va * vb);
    }
}
