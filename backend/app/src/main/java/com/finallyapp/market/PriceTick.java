package com.finallyapp.market;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * Immutable snapshot of one ticker's price.
 *
 * @param sessionOpenPrice first price seen for this ticker since the server started (base for "daily" change %)
 */
public record PriceTick(
        String ticker,
        BigDecimal price,
        BigDecimal previousPrice,
        Instant timestamp,
        BigDecimal sessionOpenPrice) {

    public String direction() {
        int c = price.compareTo(previousPrice);
        return c > 0 ? "up" : c < 0 ? "down" : "flat";
    }

    public BigDecimal change() {
        return price.subtract(previousPrice);
    }

    /** Percent change versus the session-open price, 4 decimals. */
    public BigDecimal changePercent() {
        if (sessionOpenPrice == null || sessionOpenPrice.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return price.subtract(sessionOpenPrice)
                .multiply(BigDecimal.valueOf(100))
                .divide(sessionOpenPrice, 4, RoundingMode.HALF_UP);
    }
}
