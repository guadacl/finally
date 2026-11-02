package com.finallyapp.market;

import java.util.Map;
import java.util.Set;

/** Seed prices, per-ticker GBM parameters and correlation structure for the simulator. */
public final class SeedPrices {

    /** Realistic starting prices for the default watchlist. */
    public static final Map<String, Double> SEED_PRICES = Map.of(
            "AAPL", 190.00,
            "GOOGL", 175.00,
            "MSFT", 420.00,
            "AMZN", 185.00,
            "TSLA", 250.00,
            "NVDA", 800.00,
            "META", 500.00,
            "JPM", 195.00,
            "V", 280.00,
            "NFLX", 600.00);

    /** sigma = annualized volatility, mu = annualized drift. */
    public record Params(double sigma, double mu) {
    }

    public static final Map<String, Params> TICKER_PARAMS = Map.of(
            "AAPL", new Params(0.22, 0.05),
            "GOOGL", new Params(0.25, 0.05),
            "MSFT", new Params(0.20, 0.05),
            "AMZN", new Params(0.28, 0.05),
            "TSLA", new Params(0.50, 0.03),
            "NVDA", new Params(0.40, 0.08),
            "META", new Params(0.30, 0.05),
            "JPM", new Params(0.18, 0.04),
            "V", new Params(0.17, 0.04),
            "NFLX", new Params(0.35, 0.05));

    /** Parameters for tickers added dynamically. */
    public static final Params DEFAULT_PARAMS = new Params(0.25, 0.05);

    public static final Set<String> TECH = Set.of("AAPL", "GOOGL", "MSFT", "AMZN", "META", "NVDA", "NFLX");
    public static final Set<String> FINANCE = Set.of("JPM", "V");

    public static final double INTRA_TECH_CORR = 0.6;
    public static final double INTRA_FINANCE_CORR = 0.5;
    public static final double CROSS_GROUP_CORR = 0.3;
    public static final double TSLA_CORR = 0.3;

    private SeedPrices() {
    }

    public static Params paramsFor(String ticker) {
        return TICKER_PARAMS.getOrDefault(ticker, DEFAULT_PARAMS);
    }
}
