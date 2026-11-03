package com.finallyapp.mail;

import com.finallyapp.config.FinallyProperties;
import com.finallyapp.contracts.Notification;
import com.finallyapp.contracts.NotificationEmailer;
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
 * {@link NotificationEmailer}: emails the card owner when a Support / Resistance card is created, deleted or hits
 * its target, through the email MCP server's {@code send_email} tool ({@link EmailGateway}).
 *
 * <ul>
 *   <li>Called by the Camunda {@code email-notify} job worker and, when Camunda is down, by the direct fallback,
 *       so every event is emailed once. {@link NotificationEmailer#EMAILED} types only (never CARD_MOVED).</li>
 *   <li>Non-blocking: the address lookup and the MCP call run on a single background thread with a bounded
 *       queue; when the queue is full the email is dropped (logged).</li>
 *   <li>Never throws: MCP / SMTP / database errors are logged and the alert flow continues.</li>
 *   <li>Skipped when mail is disabled ({@code MAIL_ENABLED=false} or no {@code EMAIL_MCP_URL}), the notification
 *       has no owner, or the owner has no email address.</li>
 *   <li>Camunda may re-deliver a job; its notification id is derived from the job key, so recently seen ids are
 *       ignored.</li>
 * </ul>
 */
@Component
public class NotificationMailer implements NotificationEmailer, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(NotificationMailer.class);
    static final int QUEUE_CAPACITY = 500;
    static final int DEDUP_SIZE = 2000;

    private final FinallyProperties.Mail props;
    private final EmailGateway gateway;
    private final UserContacts emails;
    private final Executor executor;
    private final boolean ownsExecutor;
    private final Map<UUID, Boolean> recent = new LinkedHashMap<>(64, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, Boolean> eldest) {
            return size() > DEDUP_SIZE;
        }
    };

    @Autowired
    public NotificationMailer(FinallyProperties props, EmailGateway gateway, UserContacts emails) {
        this(props.mail(), gateway, emails, newExecutor(), true);
    }

    NotificationMailer(FinallyProperties.Mail props, EmailGateway gateway, UserContacts emails,
                    Executor executor, boolean ownsExecutor) {
        this.props = props;
        this.gateway = gateway;
        this.emails = emails;
        this.executor = executor;
        this.ownsExecutor = ownsExecutor;
        if (props.active()) {
            log.info("Alert emails enabled via email MCP server {}", props.mcpUrl());
        } else {
            log.info("Alert emails disabled ({})", props.enabled() ? "no EMAIL_MCP_URL" : "MAIL_ENABLED=false");
        }
    }

    private static ExecutorService newExecutor() {
        AtomicInteger n = new AtomicInteger();
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(QUEUE_CAPACITY),
                r -> {
                    Thread t = new Thread(r, "finally-mail-" + n.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                });
    }

    /**
     * Queues an email for a CARD_CREATED / CARD_DELETED / TARGET_HIT notification; ignores CARD_MOVED.
     * Never throws, never blocks.
     */
    @Override
    public void send(Notification n) {
        try {
            if (n == null || !NotificationEmailer.isEmailed(n.type()) || !props.active()) {
                return;
            }
            if (n.userId() == null || n.userId().isBlank()) {
                log.debug("No email for {} without owner ({})", n.type(), n.ticker());
                return;
            }
            if (n.id() != null && !firstTime(n.id())) {
                log.debug("Duplicate {} notification {} - email already sent", n.type(), n.id());
                return;
            }
            executor.execute(() -> deliver(n));
        } catch (RejectedExecutionException e) {
            log.warn("Email queue full, dropping {} email for {} {}", n.type(), n.ticker(), n.userId());
        } catch (RuntimeException e) {
            log.warn("Could not queue email: {}", e.toString());
        }
    }

    private synchronized boolean firstTime(UUID id) {
        return recent.put(id, Boolean.TRUE) == null;
    }

    void deliver(Notification n) {
        try {
            Optional<String> to = emails.email(n.userId());
            if (to.isEmpty()) {
                log.info("User {} has no email address; skipping {} email for {}", n.userId(), n.type(), n.ticker());
                return;
            }
            NotificationEmail email = NotificationEmail.of(n, props.alertsUrl());
            String messageId = gateway.sendEmail(to.get(), email.subject(), email.body());
            log.info("Sent email '{}' to user {} ({})", email.subject(), n.userId(), messageId);
        } catch (Exception e) {
            log.warn("Sending {} email for {} to user {} failed: {}", n.type(), n.ticker(), n.userId(), e.toString());
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
