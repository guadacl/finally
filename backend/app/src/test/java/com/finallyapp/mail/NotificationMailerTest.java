package com.finallyapp.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finallyapp.config.FinallyProperties;
import com.finallyapp.contracts.KanbanColumn;
import com.finallyapp.contracts.Notification;
import com.finallyapp.contracts.NotificationType;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class NotificationMailerTest {

    private static final FinallyProperties.Mail ON =
            new FinallyProperties.Mail(true, "http://email-mcp:3001/mcp", Duration.ofSeconds(10), "http://localhost:8000");
    private static final Executor DIRECT = Runnable::run;

    private EmailGateway gateway;
    private UserContacts emails;

    @BeforeEach
    void setUp() {
        gateway = mock(EmailGateway.class);
        when(gateway.sendEmail(anyString(), anyString(), anyString())).thenReturn("<id@finally>");
        emails = mock(UserContacts.class);
        when(emails.email("user-1")).thenReturn(Optional.of("demo@finally.local"));
    }

    private NotificationMailer mailer(FinallyProperties.Mail props, Executor executor) {
        return new NotificationMailer(props, gateway, emails, executor, false);
    }

    static Notification hit(String userId, KanbanColumn column) {
        return new Notification(UUID.randomUUID(), NotificationType.TARGET_HIT, UUID.randomUUID(), "AAPL", column,
                new BigDecimal("185"), new BigDecimal("184.97"), "AAPL hit support 185.00 (now 184.97)",
                Instant.parse("2026-11-03T10:00:00Z"), userId);
    }

    private static Notification ofType(NotificationType type) {
        return new Notification(UUID.randomUUID(), type, UUID.randomUUID(), "AAPL", KanbanColumn.SUPPORT,
                new BigDecimal("185"), null, "New Support card: AAPL @ 185.00", Instant.now(), "user-1");
    }

    @Test
    void targetHitIsEmailedToTheOwnersStoredAddress() {
        mailer(ON, DIRECT).send(hit("user-1", KanbanColumn.SUPPORT));

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(gateway).sendEmail(eq("demo@finally.local"), eq("FinAlly alert: AAPL hit Support 185.00"), body.capture());
        assertThat(body.getValue()).contains("184.97").contains("http://localhost:8000/alerts");
    }

    @Test
    void movedIsNeverEmailed() {
        NotificationMailer m = mailer(ON, DIRECT);
        m.send(ofType(NotificationType.CARD_MOVED));
        m.send(null);
        verifyNoInteractions(gateway, emails);
    }

    @Test
    void createdAndDeletedAreEmailed() {
        NotificationMailer m = mailer(ON, DIRECT);
        m.send(ofType(NotificationType.CARD_CREATED));
        m.send(ofType(NotificationType.CARD_DELETED));
        verify(gateway).sendEmail(eq("demo@finally.local"), eq("FinAlly: new Support card AAPL @ 185.00"), anyString());
        verify(gateway).sendEmail(eq("demo@finally.local"), eq("FinAlly: Support card AAPL @ 185.00 deleted"),
                anyString());
    }

    @Test
    void userWithoutEmailIsSkipped() {
        when(emails.email("no-mail")).thenReturn(Optional.empty());
        mailer(ON, DIRECT).send(hit("no-mail", KanbanColumn.RESISTANCE));
        verify(gateway, never()).sendEmail(anyString(), anyString(), anyString());
    }

    @Test
    void notificationWithoutOwnerIsSkipped() {
        mailer(ON, DIRECT).send(hit(null, KanbanColumn.SUPPORT));
        verifyNoInteractions(gateway, emails);
    }

    @Test
    void disabledOrNoMcpUrlSendsNothing() {
        mailer(new FinallyProperties.Mail(false, "http://email-mcp:3001/mcp", null, "http://localhost:8000"), DIRECT)
                .send(hit("user-1", KanbanColumn.SUPPORT));
        mailer(new FinallyProperties.Mail(true, " ", null, "http://localhost:8000"), DIRECT)
                .send(hit("user-1", KanbanColumn.SUPPORT));
        verifyNoInteractions(gateway, emails);
    }

    @Test
    void mcpOrSmtpFailureIsSwallowed() {
        when(gateway.sendEmail(anyString(), anyString(), anyString()))
                .thenThrow(new EmailGateway.EmailException("send_email failed: SMTP error: ECONNREFUSED"));
        assertThatCode(() -> mailer(ON, DIRECT).send(hit("user-1", KanbanColumn.SUPPORT))).doesNotThrowAnyException();
        verify(gateway).sendEmail(anyString(), anyString(), anyString());
    }

    @Test
    void lookupFailureIsSwallowed() {
        when(emails.email(anyString())).thenThrow(new IllegalStateException("db down"));
        assertThatCode(() -> mailer(ON, DIRECT).send(hit("user-1", KanbanColumn.SUPPORT))).doesNotThrowAnyException();
        verify(gateway, never()).sendEmail(anyString(), anyString(), anyString());
    }

    @Test
    void fullQueueIsSwallowed() {
        Executor full = r -> {
            throw new RejectedExecutionException("full");
        };
        assertThatCode(() -> mailer(ON, full).send(hit("user-1", KanbanColumn.SUPPORT))).doesNotThrowAnyException();
        verifyNoInteractions(gateway);
    }

    @Test
    void redeliveredNotificationIsEmailedOnce() {
        NotificationMailer m = mailer(ON, DIRECT);
        Notification n = hit("user-1", KanbanColumn.SUPPORT);
        m.send(n);
        m.send(n);
        m.send(hit("user-1", KanbanColumn.SUPPORT)); // a new crossing has a new id
        verify(gateway, times(2)).sendEmail(anyString(), anyString(), anyString());
    }

    @Test
    void sendingDoesNotBlockTheCaller() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(inv -> {
            release.await(5, TimeUnit.SECONDS);
            return "<id>";
        }).when(gateway).sendEmail(anyString(), anyString(), anyString());
        NotificationMailer m = new NotificationMailer(new FinallyProperties(null, null, null, ON, null), gateway, emails);
        try {
            long start = System.nanoTime();
            m.send(hit("user-1", KanbanColumn.SUPPORT));
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(1000);
            verify(gateway, timeout(5000)).sendEmail(anyString(), anyString(), anyString());
        } finally {
            release.countDown();
            m.destroy();
        }
    }
}
