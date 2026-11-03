package com.finallyapp.mail;

import com.finallyapp.config.FinallyProperties;
import com.finallyapp.contracts.Notification;
import com.finallyapp.contracts.NotificationSms;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * {@link NotificationSms}: texts the card owner when a Support / Resistance card is created, deleted or hits its
 * target, through the notification MCP server's {@code send_sms} tool ({@link SmsGateway}).
 *
 * <ul>
 *   <li>Called by the Camunda {@code sms-notify} job worker (parallel with the email) and, when Camunda is down, by
 *       the direct fallback, so every event is texted once. Never CARD_MOVED.</li>
 *   <li>Non-blocking: phone lookup and MCP call run on one background thread with a bounded queue; when the queue
 *       is full the SMS is dropped (logged).</li>
 *   <li>Never throws: MCP / provider / database errors are logged and the flow continues.</li>
 *   <li>Skipped when SMS is disabled ({@code SMS_ENABLED=false} or no {@code EMAIL_MCP_URL}), the notification has
 *       no owner, or the owner has no phone number.</li>
 *   <li>De-duplicated by notification id; ids are channel-specific (derived from the {@code sms-notify} job key),
 *       so re-delivered jobs are texted once and never collide with the email channel.</li>
 * </ul>
 */
@Component
public class NotificationSmsSender implements NotificationSms, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(NotificationSmsSender.class);
    static final int QUEUE_CAPACITY = 500;
    static final int DEDUP_SIZE = 2000;

    private final boolean active;
    private final SmsGateway gateway;
    private final UserContacts contacts;
    private final Executor executor;
    private final boolean ownsExecutor;
    private final Map<UUID, Boolean> recent = new LinkedHashMap<>(64, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, Boolean> eldest) {
            return size() > DEDUP_SIZE;
        }
    };

    @Autowired
    public NotificationSmsSender(FinallyProperties props, SmsGateway gateway, UserContacts contacts) {
        this(props.sms().enabled() && props.mail().mcpUrl() != null && !props.mail().mcpUrl().isBlank(),
                gateway, contacts, newExecutor(), true);
        if (!active) {
            log.info("SMS notifications disabled ({})", props.sms().enabled() ? "no EMAIL_MCP_URL" : "SMS_ENABLED=false");
        } else {
            log.info("SMS notifications enabled via MCP server {}", props.mail().mcpUrl());
        }
    }

    NotificationSmsSender(boolean active, SmsGateway gateway, UserContacts contacts, Executor executor,
                          boolean ownsExecutor) {
        this.active = active;
        this.gateway = gateway;
        this.contacts = contacts;
        this.executor = executor;
        this.ownsExecutor = ownsExecutor;
    }

    private static ExecutorService newExecutor() {
        AtomicInteger n = new AtomicInteger();
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(QUEUE_CAPACITY),
                r -> {
                    Thread t = new Thread(r, "finally-sms-" + n.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                });
    }

    /** Queues an SMS for CARD_CREATED / CARD_DELETED / TARGET_HIT; ignores CARD_MOVED. Never throws, never blocks. */
    @Override
    public void send(Notification n) {
        try {
            if (n == null || !NotificationSms.isSent(n.type()) || !active) {
                return;
            }
            if (n.userId() == null || n.userId().isBlank()) {
                log.debug("No SMS for {} without owner ({})", n.type(), n.ticker());
                return;
            }
            if (n.id() != null && !firstTime(n.id())) {
                log.debug("Duplicate {} notification {} - SMS already sent", n.type(), n.id());
                return;
            }
            executor.execute(() -> deliver(n));
        } catch (RejectedExecutionException e) {
            log.warn("SMS queue full, dropping {} SMS for {} {}", n.type(), n.ticker(), n.userId());
        } catch (RuntimeException e) {
            log.warn("Could not queue SMS: {}", e.toString());
        }
    }

    private synchronized boolean firstTime(UUID id) {
        return recent.put(id, Boolean.TRUE) == null;
    }

    void deliver(Notification n) {
        try {
            Optional<String> to = contacts.phone(n.userId());
            if (to.isEmpty()) {
                log.info("User {} has no phone number; skipping {} SMS for {}", n.userId(), n.type(), n.ticker());
                return;
            }
            String text = NotificationSmsText.of(n);
            String messageId = gateway.sendSms(to.get(), text);
            log.info("Sent SMS '{}' to user {} ({})", text, n.userId(), messageId);
        } catch (Exception e) {
            log.warn("Sending {} SMS for {} to user {} failed: {}", n.type(), n.ticker(), n.userId(), e.toString());
        }
    }

    @Override
    public void destroy() {
        if (ownsExecutor && executor instanceof ExecutorService pool) {
            pool.shutdown();
            try {
                pool.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
