package cloud.cholewa.presence.service;

import cloud.cholewa.commons.notification.NotificationPublisher;
import cloud.cholewa.presence.config.PresenceAlertProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DetectionAlertsTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Warsaw");
    private static final Instant T0 = Instant.parse("2026-11-16T23:00:00Z");
    private static final String CERTIFICATE = "UniFi unreachable: CertificateException";

    @Mock
    private NotificationPublisher notificationPublisher;

    private final MutableClock clock = new MutableClock();
    private DetectionAlerts sut;

    @BeforeEach
    void setUp() {
        sut = new DetectionAlerts(notificationPublisher, new PresenceAlertProperties(Duration.ofMinutes(10)), clock);
        lenient().when(notificationPublisher.publishAlert(anyString())).thenReturn(Mono.empty());
        lenient().when(notificationPublisher.publishReminder(anyString())).thenReturn(Mono.empty());
        lenient().when(notificationPublisher.publishInfo(anyString())).thenReturn(Mono.empty());
    }

    @Test
    void should_alert_with_the_cause_and_where_the_fix_of_a_certificate_is() {
        evaluatedAt(0);
        skippedAt(10, CERTIFICATE);

        verify(notificationPublisher).publishAlert("Presence detection not running: no pass evaluated since"
            + " 2026-11-17 00:00 (10 min) - UniFi unreachable: CertificateException. The last known presence is kept"
            + " and served, so every reader sees the house as it was. The gateway serves another certificate:"
            + " set unifi.certificate-fingerprint (README of presence-service).");
    }

    @Test
    void should_give_no_hint_for_another_cause() {
        evaluatedAt(0);
        skippedAt(10, "database-service answered: 503");

        verify(notificationPublisher).publishAlert("Presence detection not running: no pass evaluated since"
            + " 2026-11-17 00:00 (10 min) - database-service answered: 503. The last known presence is kept"
            + " and served, so every reader sees the house as it was.");
    }

    @Test
    void should_publish_nothing_for_a_single_skipped_pass_nor_after_a_start() {
        skippedAt(0, CERTIFICATE);
        evaluatedAt(1);
        skippedAt(2, CERTIFICATE);

        verify(notificationPublisher, never()).publishAlert(anyString());
        verify(notificationPublisher, never()).publishInfo(anyString());
    }

    @Test
    void should_remind_every_hour_and_tell_the_return_once() {
        evaluatedAt(0);
        for (int minute = 1; minute <= 133; minute++) {
            skippedAt(minute, CERTIFICATE);
        }
        evaluatedAt(134);
        evaluatedAt(135);

        verify(notificationPublisher, times(1)).publishAlert(anyString());
        verify(notificationPublisher, times(2)).publishReminder(anyString());
        verify(notificationPublisher).publishReminder("Presence detection still not running since 2026-11-17 00:00"
            + " (1 h 10 min) - UniFi unreachable: CertificateException. The gateway serves another certificate:"
            + " set unifi.certificate-fingerprint (README of presence-service).");
        verify(notificationPublisher).publishInfo(
            "Presence detection running again after 2 h 14 min (down since 2026-11-17 00:00).");
    }

    //HAS-476: a failed publish fails nothing; the alert is offered again and announced with the return
    @Test
    void should_swallow_a_failed_publish_and_announce_the_alert_with_the_return() {
        when(notificationPublisher.publishAlert(anyString())).thenReturn(Mono.error(new IllegalStateException("broker down")));
        evaluatedAt(0);

        assertThatCode(() -> {
            skippedAt(10, CERTIFICATE);
            skippedAt(11, CERTIFICATE);
        }).doesNotThrowAnyException();
        evaluatedAt(12);

        verify(notificationPublisher, times(2)).publishAlert(anyString());
        verify(notificationPublisher).publishInfo("Presence detection running again after 12 min (down since"
            + " 2026-11-17 00:00). The alert could not be told while it lasted: UniFi unreachable: CertificateException.");
    }

    //a publish that never answers does not hold the caller: waiting for Mono.never() would never return
    @Test
    void should_not_wait_for_a_publish_that_never_answers() {
        when(notificationPublisher.publishAlert(anyString())).thenReturn(Mono.never());
        evaluatedAt(0);

        assertThatCode(() -> skippedAt(10, CERTIFICATE)).doesNotThrowAnyException();

        verify(notificationPublisher).publishAlert(anyString());
        assertThat(DetectionAlerts.PUBLISH_TIMEOUT).hasSeconds(30);
    }

    @Test
    void should_tell_a_return_again_when_it_did_not_get_through() {
        when(notificationPublisher.publishInfo(anyString()))
            .thenReturn(Mono.error(new IllegalStateException("broker down")))
            .thenReturn(Mono.empty());
        evaluatedAt(0);
        skippedAt(10, CERTIFICATE);
        evaluatedAt(11);
        evaluatedAt(12);
        evaluatedAt(13);

        verify(notificationPublisher, times(2)).publishInfo(anyString());
    }

    @Test
    void should_word_a_duration_in_hours_and_minutes() {
        assertThat(DetectionAlerts.duration(Duration.ofSeconds(59))).isEqualTo("0 min");
        assertThat(DetectionAlerts.duration(Duration.ofMinutes(59))).isEqualTo("59 min");
        assertThat(DetectionAlerts.duration(Duration.ofHours(49).plusMinutes(5))).isEqualTo("49 h 5 min");
    }

    private void evaluatedAt(final int minute) {
        clock.now = T0.plus(Duration.ofMinutes(minute));
        sut.evaluated();
    }

    private void skippedAt(final int minute, final String cause) {
        clock.now = T0.plus(Duration.ofMinutes(minute));
        sut.skipped(cause);
    }

    //a hand-written clock instead of a Mockito mock: Mockito cannot mock the sealed ZoneId
    private static final class MutableClock extends Clock {
        private Instant now = T0;

        @Override
        public ZoneId getZone() {
            return ZONE;
        }

        @Override
        public Clock withZone(final ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
