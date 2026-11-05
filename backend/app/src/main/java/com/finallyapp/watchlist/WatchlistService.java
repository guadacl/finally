package com.finallyapp.watchlist;

import com.finallyapp.api.model.WatchlistItem;
import com.finallyapp.config.Tickers;
import com.finallyapp.error.ApiException;
import com.finallyapp.error.ErrorCodes;
import com.finallyapp.market.PriceCache;
import com.finallyapp.market.PriceTick;
import com.finallyapp.market.TickerNames;
import com.finallyapp.market.TickerTracker;
import com.finallyapp.persistence.WatchlistEntryEntity;
import com.finallyapp.persistence.WatchlistRepository;
import com.finallyapp.security.CurrentUser;
import java.time.Duration;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class WatchlistService {

    /** How long adding a ticker waits for its company name to be looked up. */
    static final Duration NAME_WAIT = Duration.ofSeconds(3);

    private final WatchlistRepository repo;
    private final PriceCache cache;
    private final TickerTracker tracker;
    private final TickerNames names;

    public WatchlistService(WatchlistRepository repo, PriceCache cache, TickerTracker tracker, TickerNames names) {
        this.repo = repo;
        this.cache = cache;
        this.tracker = tracker;
        this.names = names;
    }

    public List<WatchlistItem> list() {
        return entries().stream().map(e -> toItem(e, names.get(e.getTicker()).orElse(null))).toList();
    }

    public List<String> tickers() {
        return entries().stream().map(WatchlistEntryEntity::getTicker).toList();
    }

    private List<WatchlistEntryEntity> entries() {
        return repo.findByUserIdOrderByAddedAtAscTickerAsc(CurrentUser.id());
    }

    /** Adds a ticker (400 INVALID_TICKER, 409 TICKER_EXISTS) and starts its live prices. */
    public WatchlistItem add(String rawTicker) {
        String ticker = Tickers.normalize(rawTicker);
        String user = CurrentUser.id();
        if (repo.existsByUserIdAndTicker(user, ticker)) {
            throw exists(ticker);
        }
        WatchlistEntryEntity saved;
        try {
            saved = repo.saveAndFlush(new WatchlistEntryEntity(user, ticker));
        } catch (DataIntegrityViolationException e) {
            throw exists(ticker); // added concurrently (unique user_id + ticker)
        }
        tracker.track(ticker);
        // a new ticker's name is usually not cached yet: give the lookup a moment so the row shows it at once
        return toItem(saved, names.await(ticker, NAME_WAIT).orElse(null));
    }

    /** Removes a ticker (404 NOT_FOUND); stops its prices unless a card or position still needs them. */
    public void remove(String rawTicker) {
        String ticker = rawTicker == null ? "" : rawTicker.trim().toUpperCase();
        if (repo.deleteByUserIdAndTicker(CurrentUser.id(), ticker) == 0) {
            throw ApiException.notFound(ErrorCodes.NOT_FOUND, ticker + " is not on the watchlist");
        }
        tracker.untrackIfUnused(ticker);
    }

    private static ApiException exists(String ticker) {
        return ApiException.conflict(ErrorCodes.TICKER_EXISTS, ticker + " is already on the watchlist");
    }

    private WatchlistItem toItem(WatchlistEntryEntity e, String name) {
        WatchlistItem item = new WatchlistItem(e.getTicker(), e.getAddedAt()).name(name);
        PriceTick t = cache.get(e.getTicker()).orElse(null);
        if (t != null) {
            item.price(t.price()).previousPrice(t.previousPrice()).sessionOpenPrice(t.sessionOpenPrice())
                    .changePercent(t.changePercent());
        }
        return item;
    }
}
