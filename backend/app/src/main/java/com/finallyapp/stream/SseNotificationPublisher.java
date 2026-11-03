package com.finallyapp.stream;

import com.finallyapp.contracts.Notification;
import com.finallyapp.contracts.NotificationPublisher;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Pushes notifications to the browsers of the notification's owner ({@link Notification#userId()}) that are
 * connected to {@code /api/stream/notifications}. One {@link SseHub} per user. Thread-safe, non-blocking.
 */
@Component
public class SseNotificationPublisher implements NotificationPublisher {

    private static final Logger log = LoggerFactory.getLogger(SseNotificationPublisher.class);

    private final Map<String, SseHub> hubs = new ConcurrentHashMap<>();

    @Override
    public void publish(Notification notification) {
        String user = notification.userId();
        if (user == null) {
            log.warn("Dropping notification without owner: {} {}", notification.type(), notification.message());
            return;
        }
        log.info("Notification {} {} for {}: {}", notification.type(), notification.ticker(), user,
                notification.message());
        SseHub hub = hubs.get(user);
        if (hub != null) {
            hub.broadcast("notification", notification);
        }
    }

    SseEmitter register(String userId) {
        return hubs.computeIfAbsent(userId, u -> new SseHub("notifications:" + u)).register(null);
    }

    void heartbeat() {
        // Empty hubs are kept (one small object per user): removing them could race with a new registration.
        hubs.values().forEach(SseHub::heartbeat);
    }

    public int clients() {
        return hubs.values().stream().mapToInt(SseHub::size).sum();
    }
}
