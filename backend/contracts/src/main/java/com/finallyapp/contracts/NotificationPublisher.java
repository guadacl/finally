package com.finallyapp.contracts;

/**
 * Pushes a notification to connected browsers (SSE {@code /api/stream/notifications}).
 * Implemented by the app; called by the workflow module's {@code finally-notify} job worker.
 * Must be thread-safe and non-blocking.
 */
public interface NotificationPublisher {
    void publish(Notification notification);
}
