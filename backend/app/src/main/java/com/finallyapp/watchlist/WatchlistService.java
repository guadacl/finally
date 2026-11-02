package com.finallyapp.watchlist;

import com.finallyapp.api.model.WatchlistItem;
import com.finallyapp.config.Tickers;
import com.finallyapp.error.ApiException;
import com.finallyapp.error.ErrorCodes;
import com.finallyapp.market.PriceCache;
import com.finallyapp.market.PriceTick;
import com.finallyapp.market.TickerTracker;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class WatchlistService {

    static final String USER = "default";

    private final JdbcClient jdbc;
    private final PriceCache cache;
    private final TickerTracker tracker;

    public WatchlistService(JdbcClient jdbc, PriceCache cache, TickerTracker tracker) {
        this.jdbc = jdbc;
        this.cache = cache;
        this.tracker = tracker;
    }

    private record Row(String ticker, OffsetDateTime addedAt) {
    }

    public List<WatchlistItem> list() {
        return jdbc.sql("SELECT ticker, added_at FROM watchlist WHERE user_id = :u ORDER BY added_at, ticker")
                .param("u", USER)
                .query((rs, i) -> new Row(rs.getString(1), rs.getObject(2, OffsetDateTime.class)))
                .list().stream().map(this::toItem).toList();
    }

    public List<String> tickers() {
        return jdbc.sql("SELECT ticker FROM watchlist WHERE user_id = :u ORDER BY added_at, ticker")
                .param("u", USER).query(String.class).list();
    }

    /** Adds a ticker (400 INVALID_TICKER, 409 TICKER_EXISTS) and starts its live prices. */
    public WatchlistItem add(String rawTicker) {
        String ticker = Tickers.normalize(rawTicker);
        Row row = jdbc.sql("""
                        INSERT INTO watchlist (user_id, ticker) VALUES (:u, :t)
                        ON CONFLICT (user_id, ticker) DO NOTHING
                        RETURNING ticker, added_at
                        """)
                .param("u", USER).param("t", ticker)
                .query((rs, i) -> new Row(rs.getString(1), rs.getObject(2, OffsetDateTime.class)))
                .optional()
                .orElseThrow(() -> ApiException.conflict(ErrorCodes.TICKER_EXISTS, ticker + " is already on the watchlist"));
        tracker.track(ticker);
        return toItem(row);
    }

    /** Removes a ticker (404 NOT_FOUND); stops its prices unless a card or position still needs them. */
    public void remove(String rawTicker) {
        String ticker = rawTicker == null ? "" : rawTicker.trim().toUpperCase();
        int n = jdbc.sql("DELETE FROM watchlist WHERE user_id = :u AND ticker = :t")
                .param("u", USER).param("t", ticker).update();
        if (n == 0) {
            throw ApiException.notFound(ErrorCodes.NOT_FOUND, ticker + " is not on the watchlist");
        }
        tracker.untrackIfUnused(ticker);
    }

    private WatchlistItem toItem(Row r) {
        WatchlistItem item = new WatchlistItem(r.ticker(), r.addedAt());
        PriceTick t = cache.get(r.ticker()).orElse(null);
        if (t != null) {
            item.price(t.price()).previousPrice(t.previousPrice()).sessionOpenPrice(t.sessionOpenPrice())
                    .changePercent(t.changePercent());
        }
        return item;
    }
}
