package com.finallyapp.portfolio;

import com.finallyapp.error.ApiException;
import com.finallyapp.error.ErrorCodes;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** Pure trade and valuation math (no I/O), all in {@link BigDecimal}. */
public final class PortfolioMath {

    public static final int QTY_SCALE = 6;
    public static final int MONEY_SCALE = 4;
    static final BigDecimal MAX_QUANTITY = new BigDecimal("1000000000");

    private PortfolioMath() {
    }

    /** Current holding (null when none). */
    public record Holding(BigDecimal quantity, BigDecimal avgCost) {
    }

    /** Outcome of a fill: new cash, new holding (null when the position is closed), and the gross amount. */
    public record Fill(BigDecimal cash, Holding holding, BigDecimal amount) {
    }

    /** Validates and normalizes an order quantity (> 0, at most 6 decimals). */
    public static BigDecimal validateQuantity(BigDecimal qty) {
        if (qty == null || qty.signum() <= 0) {
            throw ApiException.badRequest(ErrorCodes.INVALID_QUANTITY, "Quantity must be greater than 0");
        }
        if (qty.compareTo(MAX_QUANTITY) > 0) {
            throw ApiException.badRequest(ErrorCodes.INVALID_QUANTITY, "Quantity is too large");
        }
        BigDecimal q = qty.setScale(QTY_SCALE, RoundingMode.HALF_UP);
        if (q.signum() <= 0) {
            throw ApiException.badRequest(ErrorCodes.INVALID_QUANTITY, "Quantity must be at least 0.000001");
        }
        return q;
    }

    /** Market buy: cash decreases by qty*price; avg cost is the quantity-weighted average. */
    public static Fill buy(BigDecimal cash, Holding current, BigDecimal qty, BigDecimal price) {
        BigDecimal cost = qty.multiply(price).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        if (cost.compareTo(cash) > 0) {
            throw ApiException.badRequest(ErrorCodes.INSUFFICIENT_CASH,
                    "Insufficient cash: need $" + money(cost) + ", have $" + money(cash));
        }
        BigDecimal newQty;
        BigDecimal newAvg;
        if (current == null || current.quantity().signum() == 0) {
            newQty = qty;
            newAvg = price.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        } else {
            newQty = current.quantity().add(qty);
            newAvg = current.quantity().multiply(current.avgCost()).add(qty.multiply(price))
                    .divide(newQty, MONEY_SCALE, RoundingMode.HALF_UP);
        }
        return new Fill(cash.subtract(cost), new Holding(newQty.setScale(QTY_SCALE, RoundingMode.HALF_UP), newAvg), cost);
    }

    /** Market sell: cash increases by qty*price; avg cost unchanged; holding closed (null) at zero. */
    public static Fill sell(BigDecimal cash, Holding current, BigDecimal qty, BigDecimal price, String ticker) {
        BigDecimal held = current == null ? BigDecimal.ZERO : current.quantity();
        if (qty.compareTo(held) > 0) {
            throw ApiException.badRequest(ErrorCodes.INSUFFICIENT_SHARES,
                    "Insufficient shares: trying to sell " + plain(qty) + " " + ticker + ", own " + plain(held));
        }
        BigDecimal proceeds = qty.multiply(price).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal remaining = held.subtract(qty).setScale(QTY_SCALE, RoundingMode.HALF_UP);
        Holding h = remaining.signum() == 0 ? null : new Holding(remaining, current.avgCost());
        return new Fill(cash.add(proceeds), h, proceeds);
    }

    public static BigDecimal marketValue(BigDecimal qty, BigDecimal price) {
        return qty.multiply(price).setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal unrealizedPnl(BigDecimal qty, BigDecimal avgCost, BigDecimal price) {
        return price.subtract(avgCost).multiply(qty).setScale(2, RoundingMode.HALF_UP);
    }

    /** Percent change of price vs avg cost, 2 decimals (5.25 = +5.25%). */
    public static BigDecimal pnlPercent(BigDecimal avgCost, BigDecimal price) {
        if (avgCost == null || avgCost.signum() == 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        return price.subtract(avgCost).multiply(BigDecimal.valueOf(100)).divide(avgCost, 2, RoundingMode.HALF_UP);
    }

    public static String money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    public static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }
}
