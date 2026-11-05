package com.finallyapp.alerts;

import com.finallyapp.api.model.PriceAlert;
import com.finallyapp.api.model.PriceAlertCreate;
import com.finallyapp.config.Tickers;
import com.finallyapp.error.ApiException;
import com.finallyapp.error.ErrorCodes;
import com.finallyapp.market.MarketStats;
import com.finallyapp.market.TickerTracker;
import com.finallyapp.persistence.PriceAlertEntity;
import com.finallyapp.persistence.PriceAlertRepository;
import com.finallyapp.persistence.WatchlistRepository;
import com.finallyapp.security.CurrentUser;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Price alerts shown on the Alerts page: a ticker from the user's watchlist with an upper and/or lower
 * price limit and a comment. Each user only sees and changes their own alerts.
 */
@Service
public class PriceAlertService {

    static final int MAX_COMMENT = 500;
    private static final BigDecimal MAX_PRICE = new BigDecimal("100000000000000");

    /** Validated input, ready to store. */
    record NewAlert(String ticker, BigDecimal upper, BigDecimal lower, String comment) {
    }

    private record Row(UUID id, String ticker, BigDecimal upper, BigDecimal lower, String comment,
                       OffsetDateTime createdAt) {
    }

    private final PriceAlertRepository alerts;
    private final WatchlistRepository watchlist;
    private final TickerTracker tracker;
    private final MarketStats stats;

    public PriceAlertService(PriceAlertRepository alerts, WatchlistRepository watchlist, TickerTracker tracker,
                             MarketStats stats) {
        this.alerts = alerts;
        this.watchlist = watchlist;
        this.tracker = tracker;
        this.stats = stats;
    }

    public List<PriceAlert> list() {
        Map<String, MarketStats.Stats> statsByTicker = new HashMap<>();
        return alerts.findByUserIdOrderByCreatedAtAscIdAsc(CurrentUser.id()).stream()
                .map(PriceAlertService::row).map(r -> toDto(r, statsByTicker)).toList();
    }

    /** 400 when the ticker is not on the user's watchlist or the limits/comment are invalid. */
    public PriceAlert create(PriceAlertCreate req) {
        NewAlert a = validate(req);
        String user = CurrentUser.id();
        if (!watchlist.existsByUserIdAndTicker(user, a.ticker())) {
            throw ApiException.badRequest(ErrorCodes.INVALID_TICKER,
                    a.ticker() + " is not on your watchlist - add it there first");
        }
        Row row = row(alerts.save(new PriceAlertEntity(user, a.ticker(), a.upper(), a.lower(), a.comment())));
        tracker.track(row.ticker());
        return toDto(row, new HashMap<>());
    }

    public void delete(UUID id) {
        PriceAlertEntity alert = alerts.findByIdAndUserId(id, CurrentUser.id())
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND, "Alert " + id + " not found"));
        alerts.delete(alert);
        tracker.untrackIfUnused(alert.getTicker());
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

    /** {@code statsByTicker} memoizes the market columns, so a list computes each ticker's RSI only once. */
    private PriceAlert toDto(Row r, Map<String, MarketStats.Stats> statsByTicker) {
        MarketStats.Stats s = statsByTicker.computeIfAbsent(r.ticker(), stats::of);
        return new PriceAlert(r.id(), r.ticker(), r.createdAt())
                .upperLimit(strip(r.upper()))
                .lowerLimit(strip(r.lower()))
                .comment(r.comment())
                .currentPrice(s.price())
                .dayHigh(s.dayHigh())
                .dayLow(s.dayLow())
                .lastSessionVolume(s.lastSessionVolume())
                .lastSessionDate(s.lastSessionDate())
                .rsi(s.rsi());
    }

    private static BigDecimal strip(BigDecimal v) {
        return v == null ? null : v.stripTrailingZeros();
    }

    private static Row row(PriceAlertEntity e) {
        return new Row(e.getId(), e.getTicker(), e.getUpperLimit(), e.getLowerLimit(), e.getComment(), e.getCreatedAt());
    }
}
