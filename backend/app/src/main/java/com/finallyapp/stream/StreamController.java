package com.finallyapp.stream;

import com.finallyapp.market.PriceCache;
import com.finallyapp.market.PriceTick;
import com.finallyapp.security.CurrentUser;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Hand-written SSE endpoints (the generated {@code MarketApi} interface is intentionally not implemented). */
@RestController
public class StreamController {

    /** SSE payload of the {@code price} event (CONTRACTS.md §8). */
    public record PriceEvent(String ticker, BigDecimal price, BigDecimal previousPrice, Instant timestamp,
                             String direction) {
        static PriceEvent of(PriceTick t) {
            return new PriceEvent(t.ticker(), t.price(), t.previousPrice(), t.timestamp(), t.direction());
        }
    }

    private final PriceCache cache;
    private final SseNotificationPublisher notifications;
    private final SseHub priceHub = new SseHub("prices");
    /** ticker -> timestamp of the last tick broadcast (scheduler thread only). */
    private final Map<String, Instant> lastSent = new HashMap<>();

    public StreamController(PriceCache cache, SseNotificationPublisher notifications) {
        this.cache = cache;
        this.notifications = notifications;
    }

    @GetMapping(path = "/api/stream/prices", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> streamPrices() {
        SseEmitter emitter = priceHub.register(e -> {
            for (PriceTick t : cache.all().values()) {
                if (!priceHub.send(e, "price", PriceEvent.of(t))) {
                    return;
                }
            }
        });
        return sse(emitter);
    }

    @GetMapping(path = "/api/stream/notifications", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> streamNotifications() {
        return sse(notifications.register(CurrentUser.id()));
    }

    /** Every ~500 ms: one {@code price} event per ticker whose price changed timestamp since the last broadcast. */
    @Scheduled(fixedRateString = "${finally.stream.price-interval-ms:500}")
    public synchronized void broadcastPrices() {
        Map<String, PriceTick> all = cache.all();
        lastSent.keySet().retainAll(all.keySet());
        boolean anyClient = priceHub.size() > 0;
        for (PriceTick t : all.values()) {
            Instant prev = lastSent.put(t.ticker(), t.timestamp());
            if (anyClient && (prev == null || !prev.equals(t.timestamp()))) {
                priceHub.broadcast("price", PriceEvent.of(t));
            }
        }
    }

    @Scheduled(fixedRateString = "${finally.stream.heartbeat-ms:15000}",
            initialDelayString = "${finally.stream.heartbeat-ms:15000}")
    public void heartbeat() {
        priceHub.heartbeat();
        notifications.heartbeat();
    }

    public int priceClients() {
        return priceHub.size();
    }

    private static ResponseEntity<SseEmitter> sse(SseEmitter emitter) {
        return ResponseEntity.ok()
                .header("Cache-Control", "no-cache")
                .header("X-Accel-Buffering", "no")
                .body(emitter);
    }
}
