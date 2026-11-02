package com.finallyapp.watchlist;

import com.finallyapp.api.WatchlistApi;
import com.finallyapp.api.model.WatchlistAddRequest;
import com.finallyapp.api.model.WatchlistItem;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WatchlistController implements WatchlistApi {

    private final WatchlistService service;

    public WatchlistController(WatchlistService service) {
        this.service = service;
    }

    @Override
    public ResponseEntity<List<WatchlistItem>> getWatchlist() {
        return ResponseEntity.ok(service.list());
    }

    @Override
    public ResponseEntity<WatchlistItem> addToWatchlist(WatchlistAddRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.add(req.getTicker()));
    }

    @Override
    public ResponseEntity<Void> removeFromWatchlist(String ticker) {
        service.remove(ticker);
        return ResponseEntity.noContent().build();
    }
}
