package com.finallyapp.alerts;

import com.finallyapp.api.model.PriceAlert;
import com.finallyapp.api.model.PriceAlertCreate;
import com.finallyapp.config.Tickers;
import com.finallyapp.error.ApiException;
import com.finallyapp.error.ErrorCodes;
import com.finallyapp.market.DayRangeCache;
import com.finallyapp.market.LastSessionVolumes;
import com.finallyapp.market.PriceCache;
import com.finallyapp.market.TickerTracker;
import com.finallyapp.security.CurrentUser;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Price alerts shown on the Alerts page: a ticker from the user's watchlist with an upper and/or lower
 * price limit and a comment. Each user only sees and changes their own alerts.
 */
@Service
public class PriceAlertService {

    static final int MAX_COMMENT = 500;
    private static final BigDecimal MAX_PRICE = new BigDecimal("100000000000000");
    private static final String COLS = "id, ticker, upper_limit, lower_limit, comment, created_at";

    /** Validated input, ready to store. */
    record NewAlert(String ticker, BigDecimal upper, BigDecimal lower, String comment) {
    }

    private record Row(UUID id, String ticker, BigDecimal upper, BigDecimal lower, String comment,
                       OffsetDateTime createdAt) {
    }

    private final JdbcClient jdbc;
    private final PriceCache cache;
    private final TickerTracker tracker;
    private final DayRangeCache dayRanges;
    private final LastSessionVolumes volumes;

    public PriceAlertService(JdbcClient jdbc, PriceCache cache, TickerTracker tracker, DayRangeCache dayRanges,
                             LastSessionVolumes volumes) {
        this.jdbc = jdbc;
        this.cache = cache;
        this.tracker = tracker;
        this.dayRanges = dayRanges;
        this.volumes = volumes;
    }

    public List<PriceAlert> list() {
        return jdbc.sql("SELECT " + COLS + " FROM price_alerts WHERE user_id = :u ORDER BY created_at, id")
                .param("u", CurrentUser.id())
                .query(PriceAlertService::map).list()
                .stream().map(this::toDto).toList();
    }

    /** 400 when the ticker is not on the user's watchlist or the limits/comment are invalid. */
    public PriceAlert create(PriceAlertCreate req) {
        NewAlert a = validate(req);
        String user = CurrentUser.id();
        boolean onWatchlist = jdbc.sql("SELECT count(*) FROM watchlist WHERE user_id = :u AND ticker = :t")
                .param("u", user).param("t", a.ticker()).query(Integer.class).single() > 0;
        if (!onWatchlist) {
            throw ApiException.badRequest(ErrorCodes.INVALID_TICKER,
                    a.ticker() + " is not on your watchlist - add it there first");
        }
        Row row = jdbc.sql("""
                        INSERT INTO price_alerts (user_id, ticker, upper_limit, lower_limit, comment)
                        VALUES (:u, :t, :up, :lo, :c)
                        RETURNING\s""" + COLS)
                .param("u", user).param("t", a.ticker())
                .param("up", a.upper(), java.sql.Types.NUMERIC).param("lo", a.lower(), java.sql.Types.NUMERIC)
                .param("c", a.comment(), java.sql.Types.VARCHAR)
                .query(PriceAlertService::map).single();
        tracker.track(row.ticker());
        return toDto(row);
    }

    public void delete(UUID id) {
        String ticker = jdbc.sql("DELETE FROM price_alerts WHERE user_id = :u AND id = :id RETURNING ticker")
                .param("u", CurrentUser.id()).param("id", id)
                .query(String.class).optional()
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND, "Alert " + id + " not found"));
        tracker.untrackIfUnused(ticker);
    }

    /** Normalizes and checks the request: ticker format, at least one positive limit, lower < upper, comment length. */
    static NewAlert validate(PriceAlertCreate req) {
        if (req == null) {
            throw ApiException.badRequest(ErrorCodes.INVALID_REQUEST, "Request body is required");
        }
        String ticker = Tickers.normalize(req.getTicker());
        BigDecimal upper = price(req.getUpperLimit(), "upperLimit");
        BigDecimal lower = price(req.getLowerLimit(), "lowerLimit");
        if (upper == null && lower == null) {
            throw ApiException.badRequest(ErrorCodes.INVALID_PRICE, "Set an upper limit, a lower limit, or both");
        }
        if (upper != null && lower != null && lower.compareTo(upper) >= 0) {
            throw ApiException.badRequest(ErrorCodes.INVALID_PRICE, "The lower limit must be below the upper limit");
        }
        String comment = req.getComment() == null ? null : req.getComment().trim();
        if (comment != null && comment.isEmpty()) {
            comment = null;
        }
        if (comment != null && comment.length() > MAX_COMMENT) {
            throw ApiException.badRequest(ErrorCodes.INVALID_REQUEST,
                    "The comment can be at most " + MAX_COMMENT + " characters");
        }
        return new NewAlert(ticker, upper, lower, comment);
    }

    private static BigDecimal price(BigDecimal p, String field) {
        if (p == null) {
            return null;
        }
        BigDecimal scaled = p.setScale(4, RoundingMode.HALF_UP);
        if (scaled.signum() <= 0) {
            throw ApiException.badRequest(ErrorCodes.INVALID_PRICE, field + " must be greater than 0");
        }
        if (scaled.compareTo(MAX_PRICE) >= 0) {
            throw ApiException.badRequest(ErrorCodes.INVALID_PRICE, field + " is too large");
        }
        return scaled;
    }

    private PriceAlert toDto(Row r) {
        PriceAlert dto = new PriceAlert(r.id(), r.ticker(), r.createdAt())
                .upperLimit(strip(r.upper()))
                .lowerLimit(strip(r.lower()))
                .comment(r.comment())
                .currentPrice(cache.price(r.ticker()));
        dayRanges.get(r.ticker()).ifPresent(d -> dto.dayHigh(d.high()).dayLow(d.low()));
        volumes.get(r.ticker()).ifPresent(v -> dto.lastSessionVolume(v.volume()).lastSessionDate(v.session()));
        return dto;
    }

    private static BigDecimal strip(BigDecimal v) {
        return v == null ? null : v.stripTrailingZeros();
    }

    private static Row map(ResultSet rs, int i) throws SQLException {
        return new Row(rs.getObject(1, UUID.class), rs.getString(2), rs.getBigDecimal(3), rs.getBigDecimal(4),
                rs.getString(5), rs.getObject(6, OffsetDateTime.class));
    }
}
