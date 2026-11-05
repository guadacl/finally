package com.finallyapp.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finallyapp.contracts.KanbanColumn;
import com.finallyapp.contracts.Notification;
import com.finallyapp.contracts.NotificationType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NotificationSmsSenderTest {

    private static final Executor DIRECT = Runnable::run;

    private SmsGateway gateway;
    private UserContacts contacts;

    @BeforeEach
    void setUp() {
        gateway = mock(SmsGateway.class);
        when(gateway.sendSms(anyString(), anyString())).thenReturn("sms-1");
        contacts = mock(UserContacts.class);
        when(contacts.phone("user-1")).thenReturn(Optional.of("+32470123456"));
    }

    private NotificationSmsSender sender(boolean active, Executor executor) {
        return new NotificationSmsSender(active, gateway, contacts, executor, false);
    }

    private static Notification of(NotificationType type, String userId, String price) {
        return new Notification(UUID.randomUUID(), type, UUID.randomUUID(), "AAPL", KanbanColumn.SUPPORT,
                new BigDecimal("185"), price == null ? null : new BigDecimal(price), "msg",
                Instant.parse("2026-11-03T10:00:00Z"), userId);
    }

    @Test
    void textsCreatedDeletedAndTargetHitToTheStoredNumber() {
        NotificationSmsSender s = sender(true, DIRECT);
        s.send(of(NotificationType.TARGET_HIT, "user-1", "184.97"));
        s.send(of(NotificationType.CARD_CREATED, "user-1", null));
        s.send(of(NotificationType.CARD_DELETED, "user-1", null));
        verify(gateway).sendSms("+32470123456", "Tradex: AAPL hit Support 185.00 (now 184.97)");
        verify(gateway).sendSms("+32470123456", "Tradex: new Support card AAPL @ 185.00");
        verify(gateway).sendSms("+32470123456", "Tradex: Support card AAPL @ 185.00 deleted");
    }

    @Test
    void movedIsNeverTexted() {
        sender(true, DIRECT).send(of(NotificationType.CARD_MOVED, "user-1", null));
        sender(true, DIRECT).send(null);
        verifyNoInteractions(gateway, contacts);
    }

    @Test
    void userWithoutPhoneIsSkipped() {
        when(contacts.phone("no-phone")).thenReturn(Optional.empty());
        sender(true, DIRECT).send(of(NotificationType.TARGET_HIT, "no-phone", "184.97"));
        verify(gateway, never()).sendSms(anyString(), anyString());
    }

    @Test
    void disabledOrOwnerlessSendsNothing() {
        sender(false, DIRECT).send(of(NotificationType.TARGET_HIT, "user-1", "184.97"));
        sender(true, DIRECT).send(of(NotificationType.TARGET_HIT, null, "184.97"));
        verifyNoInteractions(gateway, contacts);
    }

    @Test
    void providerAndLookupFailuresAreSwallowed() {
        when(gateway.sendSms(anyString(), anyString())).thenThrow(new SmsGateway.SmsException("send_sms failed", null));
        assertThatCode(() -> sender(true, DIRECT).send(of(NotificationType.TARGET_HIT, "user-1", "1")))
                .doesNotThrowAnyException();
        when(contacts.phone(anyString())).thenThrow(new IllegalStateException("db down"));
        assertThatCode(() -> sender(true, DIRECT).send(of(NotificationType.TARGET_HIT, "user-1", "1")))
                .doesNotThrowAnyException();
        Executor full = r -> {
            throw new RejectedExecutionException("full");
        };
        assertThatCode(() -> sender(true, full).send(of(NotificationType.TARGET_HIT, "user-1", "1")))
                .doesNotThrowAnyException();
    }

    @Test
    void redeliveredNotificationIsTextedOnce() {
        NotificationSmsSender s = sender(true, DIRECT);
        Notification n = of(NotificationType.TARGET_HIT, "user-1", "184.97");
        s.send(n);
        s.send(n);
        s.send(of(NotificationType.TARGET_HIT, "user-1", "184.97")); // a new crossing has a new id
        verify(gateway, times(2)).sendSms(anyString(), anyString());
    }

    @Test
    void textIsAtMost160Characters() {
        String longText = NotificationSmsText.truncate("x".repeat(400));
        assertThat(longText).hasSize(160).endsWith("…");
        assertThat(NotificationSmsText.truncate("short")).isEqualTo("short");
        Notification longTicker = new Notification(UUID.randomUUID(), NotificationType.CARD_DELETED, UUID.randomUUID(),
                "T".repeat(300), KanbanColumn.RESISTANCE, BigDecimal.ONE, null, "m", Instant.now(), "user-1");
        assertThat(NotificationSmsText.of(longTicker)).hasSize(160).startsWith("Tradex: Resistance card TTT");
    }

    @Test
    void normalizesAndValidatesE164() {
        assertThat(UserContacts.normalizePhone("+32 470 12-34-56")).isEqualTo("+32470123456");
        assertThat(UserContacts.normalizePhone("(+1) 415.555.0100")).isEqualTo("+14155550100");
        assertThat(UserContacts.normalizePhone("0032470123456")).isEqualTo("+32470123456");
        assertThat(UserContacts.normalizePhone("0470123456")).isNull();      // national format
        assertThat(UserContacts.normalizePhone("+0470123456")).isNull();     // country code cannot start with 0
        assertThat(UserContacts.normalizePhone("+12345")).isNull();          // too short
        assertThat(UserContacts.normalizePhone("+1234567890123456")).isNull(); // > 15 digits
        assertThat(UserContacts.normalizePhone("call me")).isNull();
        assertThat(UserContacts.normalizePhone(null)).isNull();
    }
}
