package com.finallyapp.portfolio;

import com.finallyapp.api.model.Portfolio;
import com.finallyapp.api.model.PortfolioSnapshot;
import com.finallyapp.api.model.Position;
import com.finallyapp.api.model.Trade;
import com.finallyapp.api.model.TradeSide;
import com.finallyapp.config.Tickers;
import com.finallyapp.error.ApiException;
import com.finallyapp.error.ErrorCodes;
import com.finallyapp.market.PriceCache;
import com.finallyapp.market.TickerNames;
import com.finallyapp.market.TickerTracker;
import com.finallyapp.portfolio.PortfolioMath.Fill;
import com.finallyapp.portfolio.PortfolioMath.Holding;
import com.finallyapp.portfolio.PortfolioRepository.PositionRow;
import com.finallyapp.portfolio.PortfolioRepository.TradeRow;
import com.finallyapp.security.CurrentUser;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Market-order trading, valuation and portfolio snapshots. */
@Service
public class PortfolioService {

    private static final Logger log = LoggerFactory.getLogger(PortfolioService.class);

    /** A filled trade plus the portfolio right after it. */
    public record TradeOutcome(Trade trade, Portfolio portfolio) {
    }

    private final PortfolioRepository repo;
    private final PriceCache cache;
    private final TickerTracker tracker;
    private final TransactionTemplate tx;
    private final TickerNames names;

    public PortfolioService(PortfolioRepository repo, PriceCache cache, TickerTracker tracker, TransactionTemplate tx,
                            TickerNames names) {
        this.names = names;
        this.repo = repo;
        this.cache = cache;
        this.tracker = tracker;
        this.tx = tx;
    }

    public Portfolio getPortfolio() {
        return value(repo.cash(), repo.positions());
    }

    /**
     * Executes a market order at the current cached price. Validation failures throw {@link ApiException}
     * (INVALID_TICKER, INVALID_QUANTITY, UNKNOWN_TICKER, INSUFFICIENT_CASH, INSUFFICIENT_SHARES).
     */
    public TradeOutcome executeTrade(String rawTicker, TradeSide side, BigDecimal rawQty) {
        String ticker = Tickers.normalize(rawTicker);
        BigDecimal qty = PortfolioMath.validateQuantity(rawQty);
        if (side == null) {
            throw ApiException.badRequest(ErrorCodes.INVALID_REQUEST, "side must be buy or sell");
        }
        boolean newlyTracked = false;
        BigDecimal price = cache.price(ticker);
        if (price == null && side == TradeSide.BUY) {
            // Ticker not priced yet: start tracking it (the simulator prices it immediately).
            newlyTracked = !tracker.isTracked(ticker);
            tracker.track(ticker);
            price = cache.price(ticker);
        }
        if (price == null) {
            if (newlyTracked) {
                safeUntrack(ticker);
            }
            throw ApiException.badRequest(ErrorCodes.UNKNOWN_TICKER, "No price available for " + ticker);
        }
        final BigDecimal fillPrice = price;
        try {
            TradeOutcome outcome = tx.execute(status -> {
                BigDecimal cash = repo.lockCash();
                Holding current = repo.holding(ticker).orElse(null);
                Fill fill = side == TradeSide.BUY
                        ? PortfolioMath.buy(cash, current, qty, fillPrice)
                        : PortfolioMath.sell(cash, current, qty, fillPrice, ticker);
                repo.setCash(fill.cash());
                repo.saveHolding(ticker, fill.holding());
                TradeRow row = repo.insertTrade(ticker, side.getValue(), qty, fillPrice);
                Portfolio after = value(fill.cash(), repo.positions());
                repo.insertSnapshot(after.getTotalValue());
                return new TradeOutcome(toTrade(row), after);
            });
            log.info("Trade: {} {} {} @ {}", side.getValue(), PortfolioMath.plain(qty), ticker, fillPrice);
            if (side == TradeSide.SELL) {
                safeUntrack(ticker);
            }
            return outcome;
        } catch (ApiException e) {
            if (newlyTracked) {
                safeUntrack(ticker);
            }
            throw e;
        }
    }

    public List<PortfolioSnapshot> history() {
        return repo.snapshots(2000).stream()
                .map(s -> new PortfolioSnapshot(s.totalValue().setScale(2, RoundingMode.HALF_UP), s.recordedAt()))
                .toList();
    }

    /** Records every user's total portfolio value every 30 s (first one shortly after startup). */
    @Scheduled(initialDelayString = "${finally.portfolio.snapshot-initial-delay-ms:5000}",
            fixedRateString = "${finally.portfolio.snapshot-interval-ms:30000}")
    public void recordSnapshot() {
        List<String> users;
        try {
            users = repo.userIds();
        } catch (RuntimeException e) {
            log.warn("Portfolio snapshot failed: {}", e.toString());
            return;
        }
        for (String user : users) {
            try {
                CurrentUser.runAs(user, () -> repo.insertSnapshot(getPortfolio().getTotalValue()));
            } catch (RuntimeException e) {
                log.warn("Portfolio snapshot for {} failed: {}", user, e.toString());
            }
        }
    }

    /** Values positions at the cached price (falls back to avg cost when no price is known yet). */
    Portfolio value(BigDecimal cash, List<PositionRow> rows) {
        List<Position> positions = new ArrayList<>();
        BigDecimal positionsValue = BigDecimal.ZERO;
        BigDecimal pnl = BigDecimal.ZERO;
        for (PositionRow r : rows) {
            BigDecimal price = cache.price(r.ticker());
            if (price == null) {
                price = r.avgCost();
            }
            BigDecimal mv = PortfolioMath.marketValue(r.quantity(), price);
            BigDecimal upnl = PortfolioMath.unrealizedPnl(r.quantity(), r.avgCost(), price);
            positions.add(new Position(r.ticker(), r.quantity().stripTrailingZeros(), r.avgCost(), price, mv, upnl,
                    PortfolioMath.pnlPercent(r.avgCost(), price)).name(names.get(r.ticker()).orElse(null)));
            positionsValue = positionsValue.add(mv);
            pnl = pnl.add(upnl);
        }
        BigDecimal cash2 = cash.setScale(2, RoundingMode.HALF_UP);
        return new Portfolio(cash2, positionsValue, cash2.add(positionsValue), pnl, positions);
    }

    private static Trade toTrade(TradeRow r) {
        return new Trade(r.id(), r.ticker(), TradeSide.fromValue(r.side()), r.quantity().stripTrailingZeros(),
                r.price(), r.executedAt());
    }

    private void safeUntrack(String ticker) {
        try {
            tracker.untrackIfUnused(ticker);
        } catch (RuntimeException e) {
            log.debug("untrack {} failed: {}", ticker, e.toString());
        }
    }
}
