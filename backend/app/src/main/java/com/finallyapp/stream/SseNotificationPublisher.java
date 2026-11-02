package com.finallyapp.stream;

import com.finallyapp.contracts.Notification;
import com.finallyapp.contracts.NotificationPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Pushes notifications to every browser connected to {@code /api/stream/notifications}. Thread-safe, non-blocking. */
@Component
public class SseNotificationPublisher implements NotificationPublisher {

    private static final Logger log = LoggerFactory.getLogger(SseNotificationPublisher.class);

    private final SseHub hub = new SseHub("notifications");

    @Override
    public void publish(Notification notification) {
        log.info("Notification {} {}: {}", notification.type(), notification.ticker(), notification.message());
        hub.broadcast("notification", notification);
    }

    SseEmitter register() {
        return hub.register(null);
    }

    void heartbeat() {
        hub.heartbeat();
    }

    public int clients() {
        return hub.size();
    }
}
