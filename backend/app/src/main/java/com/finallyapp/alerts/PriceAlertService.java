package com.finallyapp.alerts;

import com.finallyapp.api.model.PriceAlert;
import com.finallyapp.api.model.PriceAlertCreate;
import com.finallyapp.config.Tickers;
import com.finallyapp.error.ApiException;
import com.finallyapp.error.ErrorCodes;
import com.finallyapp.market.DailyCloses;
import com.finallyapp.market.DayRangeCache;
import com.finallyapp.market.LastSessionVolumes;
import com.finallyapp.market.PriceCache;
import com.finallyapp.market.TickerNames;
import com.finallyapp.market.TickerTracker;
import com.finallyapp.persistence.PriceAlertEntity;
import com.finallyapp.persistence.PriceAlertRepository;
import com.finallyapp.persistence.WatchlistRepository;
import com.finallyapp.security.CurrentUser;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final PriceCache cache;
    private final TickerTracker tracker;
    private final DayRangeCache dayRanges;
    private final LastSessionVolumes volumes;
    private final DailyCloses dailyCloses;
    private final TickerNames names;

    public PriceAlertService(PriceAlertRepository alerts, WatchlistRepository watchlist, PriceCache cache,
                             TickerTracker tracker, DayRangeCache dayRanges, LastSessionVolumes volumes,
                             DailyCloses dailyCloses, TickerNames names) {
        this.names = names;
        this.alerts = alerts;
        this.watchlist = watchlist;
        this.cache = cache;
        this.tracker = tracker;
        this.dayRanges = dayRanges;
        this.volumes = volumes;
        this.dailyCloses = dailyCloses;
    }

    public List<PriceAlert> list() {
        return alerts.findByUserIdOrderByCreatedAtAscIdAsc(CurrentUser.id()).stream()
                .map(PriceAlertService::row).map(this::toDto).toList();
    }

    /** A created alert ({@code created}) or the ticker's existing alert, updated. */
    public record Upserted(PriceAlert alert, boolean created) {
    }

    /**
     * Adds an alert; 400 when the ticker is not on the user's watchlist or the limits/comment are invalid.
     * One alert per ticker: when the ticker already has one, its limits are replaced by the new ones and its
     * comment too when a comment is given.
     */
    @Transactional
    public Upserted create(PriceAlertCreate req) {
        NewAlert a = validate(req);
        String user = CurrentUser.id();
        if (!watchlist.existsByUserIdAndTicker(user, a.ticker())) {
            throw ApiException.badRequest(ErrorCodes.INVALID_TICKER,
                    a.ticker() + " is not on your watchlist - add it there first");
        }
        PriceAlertEntity existing = alerts.findByUserIdAndTicker(user, a.ticker()).orElse(null);
        boolean created = existing == null;
        PriceAlertEntity saved;
        if (created) {
            saved = alerts.saveAndFlush(new PriceAlertEntity(user, a.ticker(), a.upper(), a.lower(), a.comment()));
        } else {
            existing.setLimits(a.upper(), a.lower());
            if (a.comment() != null) {
                existing.setComment(a.comment());
            }
            saved = alerts.saveAndFlush(existing);
        }
        Row row = row(saved);
        tracker.track(row.ticker());
        return new Upserted(toDto(row), created);
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

    private PriceAlert toDto(Row r) {
        BigDecimal price = cache.price(r.ticker());
        PriceAlert dto = new PriceAlert(r.id(), r.ticker(), r.createdAt())
                .upperLimit(strip(r.upper()))
                .lowerLimit(strip(r.lower()))
                .comment(r.comment())
                .currentPrice(price);
        names.get(r.ticker()).ifPresent(dto::name);
        dayRanges.get(r.ticker()).ifPresent(d -> dto.dayHigh(d.high()).dayLow(d.low()));
        volumes.get(r.ticker()).ifPresent(v -> dto.lastSessionVolume(v.volume()).lastSessionDate(v.session()));
        dailyCloses.rsi(r.ticker(), price)
                .ifPresent(v -> dto.rsi(BigDecimal.valueOf(v).setScale(1, RoundingMode.HALF_UP)));
        return dto;
    }

    private static BigDecimal strip(BigDecimal v) {
        return v == null ? null : v.stripTrailingZeros();
    }

    private static Row row(PriceAlertEntity e) {
        return new Row(e.getId(), e.getTicker(), e.getUpperLimit(), e.getLowerLimit(), e.getComment(), e.getCreatedAt());
    }
}
