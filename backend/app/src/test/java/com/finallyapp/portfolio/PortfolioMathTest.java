package com.finallyapp.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finallyapp.error.ApiException;
import com.finallyapp.portfolio.PortfolioMath.Fill;
import com.finallyapp.portfolio.PortfolioMath.Holding;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PortfolioMathTest {

    private static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    private static BigDecimal q(String s) {
        return PortfolioMath.validateQuantity(d(s));
    }

    @Test
    void buyNewPosition() {
        Fill f = PortfolioMath.buy(d("10000"), null, q("10"), d("190.00"));
        assertThat(f.cash()).isEqualByComparingTo("8100");
        assertThat(f.amount()).isEqualByComparingTo("1900");
        assertThat(f.holding().quantity()).isEqualByComparingTo("10");
        assertThat(f.holding().avgCost()).isEqualByComparingTo("190");
    }

    @Test
    void buyMoreUsesWeightedAverageCost() {
        Fill f = PortfolioMath.buy(d("8100"), new Holding(d("10"), d("190")), q("5"), d("200.00"));
        assertThat(f.holding().quantity()).isEqualByComparingTo("15");
        // (10*190 + 5*200) / 15 = 193.3333
        assertThat(f.holding().avgCost()).isEqualByComparingTo("193.3333");
        assertThat(f.cash()).isEqualByComparingTo("7100");
    }

    @Test
    void overspendIsRejected() {
        assertThatThrownBy(() -> PortfolioMath.buy(d("100"), null, q("1"), d("190.00")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("INSUFFICIENT_CASH"));
    }

    @Test
    void spendingExactlyAllCashIsAllowed() {
        Fill f = PortfolioMath.buy(d("190"), null, q("1"), d("190.00"));
        assertThat(f.cash()).isEqualByComparingTo("0");
    }

    @Test
    void oversellIsRejected() {
        assertThatThrownBy(() -> PortfolioMath.sell(d("0"), new Holding(d("2"), d("100")), q("3"), d("100"), "AAPL"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("INSUFFICIENT_SHARES"));
        assertThatThrownBy(() -> PortfolioMath.sell(d("0"), null, q("1"), d("100"), "AAPL"))
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("INSUFFICIENT_SHARES"));
    }

    @Test
    void partialSellKeepsAvgCost() {
        Fill f = PortfolioMath.sell(d("0"), new Holding(d("10"), d("190")), q("4"), d("200"), "AAPL");
        assertThat(f.holding().quantity()).isEqualByComparingTo("6");
        assertThat(f.holding().avgCost()).isEqualByComparingTo("190");
        assertThat(f.cash()).isEqualByComparingTo("800");
    }

    @Test
    void sellingEverythingClosesPosition() {
        Fill f = PortfolioMath.sell(d("50"), new Holding(d("10"), d("190")), q("10"), d("180"), "AAPL");
        assertThat(f.holding()).isNull();
        assertThat(f.cash()).isEqualByComparingTo("1850");
    }

    @Test
    void sellAtALossRealizesLowerProceeds() {
        Holding h = new Holding(d("10"), d("190"));
        assertThat(PortfolioMath.unrealizedPnl(h.quantity(), h.avgCost(), d("171"))).isEqualByComparingTo("-190");
        assertThat(PortfolioMath.pnlPercent(h.avgCost(), d("171"))).isEqualByComparingTo("-10.00");
        Fill f = PortfolioMath.sell(d("8100"), h, q("10"), d("171"), "AAPL");
        assertThat(f.cash()).isEqualByComparingTo("9810"); // lost $190 vs the $10,000 start
    }

    @Test
    void fractionalShares() {
        Fill buy = PortfolioMath.buy(d("1000"), null, q("0.5"), d("190.55"));
        assertThat(buy.cash()).isEqualByComparingTo("904.725");
        assertThat(buy.holding().quantity()).isEqualByComparingTo("0.5");
        Fill sell = PortfolioMath.sell(buy.cash(), buy.holding(), q("0.123456"), d("200"), "AAPL");
        assertThat(sell.holding().quantity()).isEqualByComparingTo("0.376544");
        assertThat(sell.cash()).isEqualByComparingTo("929.4162");
        assertThat(PortfolioMath.marketValue(d("0.376544"), d("200"))).isEqualByComparingTo("75.31");
    }

    @Test
    void invalidQuantities() {
        for (String bad : new String[] {"0", "-1", "0.0000001"}) {
            assertThatThrownBy(() -> PortfolioMath.validateQuantity(d(bad)))
                    .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("INVALID_QUANTITY"));
        }
        assertThatThrownBy(() -> PortfolioMath.validateQuantity(null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> PortfolioMath.validateQuantity(d("1e12"))).isInstanceOf(ApiException.class);
    }

    @Test
    void pnlPercentWithZeroCost() {
        assertThat(PortfolioMath.pnlPercent(BigDecimal.ZERO, d("10"))).isEqualByComparingTo("0");
        assertThat(PortfolioMath.pnlPercent(d("100"), d("105.25"))).isEqualByComparingTo("5.25");
    }
}
