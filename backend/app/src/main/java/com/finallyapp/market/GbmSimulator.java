package com.finallyapp.market;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Geometric Brownian Motion simulator for correlated stock prices.
 *
 * <pre>
 *   S(t+dt) = S(t) * exp((mu - sigma^2/2) * dt + sigma * sqrt(dt) * Z)
 * </pre>
 *
 * Z is a vector of correlated standard normals obtained from the Cholesky factor of a sector-based
 * correlation matrix. dt is 500 ms expressed as a fraction of a trading year (252 days x 6.5 h),
 * so each tick moves prices by fractions of a cent that accumulate naturally. Each tick, each ticker has
 * a small chance of a sudden 2-5% "event" move for drama. Not thread-safe; callers synchronize.
 */
public class GbmSimulator {

    private static final Logger log = LoggerFactory.getLogger(GbmSimulator.class);

    public static final double TRADING_SECONDS_PER_YEAR = 252 * 6.5 * 3600; // 5,896,800
    public static final double DEFAULT_DT = 0.5 / TRADING_SECONDS_PER_YEAR;  // ~8.48e-8

    private final double dt;
    private final double eventProbability;
    private final Random random;

    private final List<String> tickers = new ArrayList<>();
    private final Map<String, Double> prices = new HashMap<>();
    private final Map<String, SeedPrices.Params> params = new HashMap<>();
    private double[][] cholesky; // null when n <= 1

    public GbmSimulator(List<String> initialTickers) {
        this(initialTickers, DEFAULT_DT, 0.001, new Random());
    }

    public GbmSimulator(List<String> initialTickers, double dt, double eventProbability, Random random) {
        this.dt = dt;
        this.eventProbability = eventProbability;
        this.random = random;
        for (String t : initialTickers) {
            addInternal(t);
        }
        rebuildCholesky();
    }

    /** Advances every ticker one step. Returns ticker -> new price rounded to cents (insertion order). */
    public Map<String, Double> step() {
        int n = tickers.size();
        Map<String, Double> result = new LinkedHashMap<>();
        if (n == 0) {
            return result;
        }
        double[] z = new double[n];
        for (int i = 0; i < n; i++) {
            z[i] = random.nextGaussian();
        }
        double[] zc = cholesky == null ? z : multiply(cholesky, z);

        double sqrtDt = Math.sqrt(dt);
        for (int i = 0; i < n; i++) {
            String ticker = tickers.get(i);
            SeedPrices.Params p = params.get(ticker);
            double drift = (p.mu() - 0.5 * p.sigma() * p.sigma()) * dt;
            double diffusion = p.sigma() * sqrtDt * zc[i];
            double price = prices.get(ticker) * Math.exp(drift + diffusion);

            if (random.nextDouble() < eventProbability) {
                double magnitude = 0.02 + random.nextDouble() * 0.03;
                int sign = random.nextBoolean() ? 1 : -1;
                price *= 1 + magnitude * sign;
                log.debug("Random event on {}: {}{}%", ticker, sign > 0 ? "+" : "-",
                        String.format("%.1f", magnitude * 100));
            }
            prices.put(ticker, price);
            result.put(ticker, round2(price));
        }
        return result;
    }

    public void addTicker(String ticker) {
        if (prices.containsKey(ticker)) {
            return;
        }
        addInternal(ticker);
        rebuildCholesky();
    }

    public void removeTicker(String ticker) {
        if (!prices.containsKey(ticker)) {
            return;
        }
        tickers.remove(ticker);
        prices.remove(ticker);
        params.remove(ticker);
        rebuildCholesky();
    }

    public Double getPrice(String ticker) {
        return prices.get(ticker);
    }

    public List<String> getTickers() {
        return List.copyOf(tickers);
    }

    double[][] choleskyFactor() {
        return cholesky;
    }

    private void addInternal(String ticker) {
        if (prices.containsKey(ticker)) {
            return;
        }
        tickers.add(ticker);
        prices.put(ticker, SeedPrices.SEED_PRICES.getOrDefault(ticker, 50.0 + random.nextDouble() * 250.0));
        params.put(ticker, SeedPrices.paramsFor(ticker));
    }

    private void rebuildCholesky() {
        int n = tickers.size();
        if (n <= 1) {
            cholesky = null;
            return;
        }
        double[][] corr = new double[n][n];
        for (int i = 0; i < n; i++) {
            corr[i][i] = 1.0;
            for (int j = i + 1; j < n; j++) {
                double rho = pairwiseCorrelation(tickers.get(i), tickers.get(j));
                corr[i][j] = rho;
                corr[j][i] = rho;
            }
        }
        cholesky = choleskyDecompose(corr);
    }

    /** Sector-based correlation: tech 0.6, finance 0.5, TSLA 0.3 with anything, cross-sector/unknown 0.3. */
    public static double pairwiseCorrelation(String t1, String t2) {
        if (t1.equals("TSLA") || t2.equals("TSLA")) {
            return SeedPrices.TSLA_CORR;
        }
        if (SeedPrices.TECH.contains(t1) && SeedPrices.TECH.contains(t2)) {
            return SeedPrices.INTRA_TECH_CORR;
        }
        if (SeedPrices.FINANCE.contains(t1) && SeedPrices.FINANCE.contains(t2)) {
            return SeedPrices.INTRA_FINANCE_CORR;
        }
        return SeedPrices.CROSS_GROUP_CORR;
    }

    /** Lower-triangular L with L * L^T = a. a must be symmetric positive definite. */
    static double[][] choleskyDecompose(double[][] a) {
        int n = a.length;
        double[][] l = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j <= i; j++) {
                double sum = a[i][j];
                for (int k = 0; k < j; k++) {
                    sum -= l[i][k] * l[j][k];
                }
                if (i == j) {
                    if (sum <= 0) {
                        throw new IllegalStateException("Correlation matrix is not positive definite");
                    }
                    l[i][i] = Math.sqrt(sum);
                } else {
                    l[i][j] = sum / l[j][j];
                }
            }
        }
        return l;
    }

    private static double[] multiply(double[][] l, double[] z) {
        int n = z.length;
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            double s = 0;
            for (int k = 0; k <= i; k++) {
                s += l[i][k] * z[k];
            }
            out[i] = s;
        }
        return out;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
