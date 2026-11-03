package com.finallyapp.market;

import java.util.List;
import java.util.OptionalDouble;

/** Relative Strength Index with Wilder's smoothing (the standard RSI shown by trading platforms). */
public final class Rsi {

    /** The usual look-back: 14 periods (here: daily closes). */
    public static final int PERIOD = 14;

    private Rsi() {
    }

    /**
     * RSI (0-100) of {@code closes}, oldest first. The first average gain/loss is a simple mean over the first
     * {@code period} changes; later ones use Wilder's smoothing {@code avg = (prev * (period - 1) + current) / period}.
     * Empty when there are fewer than {@code period + 1} closes.
     */
    public static OptionalDouble wilder(List<Double> closes, int period) {
        if (period < 1 || closes == null || closes.size() < period + 1) {
            return OptionalDouble.empty();
        }
        double gain = 0;
        double loss = 0;
        for (int i = 1; i <= period; i++) {
            double d = closes.get(i) - closes.get(i - 1);
            if (d > 0) {
                gain += d;
            } else {
                loss -= d;
            }
        }
        double avgGain = gain / period;
        double avgLoss = loss / period;
        for (int i = period + 1; i < closes.size(); i++) {
            double d = closes.get(i) - closes.get(i - 1);
            avgGain = (avgGain * (period - 1) + Math.max(d, 0)) / period;
            avgLoss = (avgLoss * (period - 1) + Math.max(-d, 0)) / period;
        }
        if (avgLoss == 0) {
            return OptionalDouble.of(avgGain == 0 ? 50 : 100);
        }
        return OptionalDouble.of(100 - 100 / (1 + avgGain / avgLoss));
    }
}
