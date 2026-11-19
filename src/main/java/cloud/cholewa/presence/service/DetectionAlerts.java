package cloud.cholewa.presence.service;

import cloud.cholewa.commons.notification.NotificationPublisher;
import cloud.cholewa.presence.config.PresenceAlertProperties;
import cloud.cholewa.presence.service.PassWatch.Notice;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

//Tells the technical channel that the detection does not run (HAS-476) - alert, reminder and info of
//notification-service, English, level error for the alert, no SMS. What to say is judged by PassWatch.
//
//A notice must neither fail nor stall the pass: it is sent on its own subscription, cut off after
//PUBLISH_TIMEOUT and swallowed, so the pass - bounded at 55 s by PresenceCron - never waits for the broker.
//An alert that did not get through is due again with the next skipped pass, and announced with the return
@Slf4j
@Component
public class DetectionAlerts {

    static final Duration PUBLISH_TIMEOUT = Duration.ofSeconds(30);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final String CERTIFICATE = "CertificateException";

    private final NotificationPublisher notificationPublisher;
    private final Clock clock;
    private final PassWatch watch;

    public DetectionAlerts(
        final NotificationPublisher notificationPublisher,
        final PresenceAlertProperties properties,
        final Clock clock
    ) {
        this.notificationPublisher = notificationPublisher;
        this.clock = clock;
        this.watch = new PassWatch(properties.skippedAfter());
    }

    /** A pass decided on what it saw - a restored state alone is not one. */
    public void evaluated() {
        watch.evaluated(clock.instant()).ifPresent(this::publish);
    }

    /** A pass skipped; {@code cause} in the fixed words of the service, never a body of the gateway. */
    public void skipped(final String cause) {
        watch.skipped(clock.instant(), cause).ifPresent(this::publish);
    }

    private void publish(final Notice notice) {
        final String text = text(notice);
        Mono.defer(() -> switch (notice.kind()) {
                case ALERT -> notificationPublisher.publishAlert(text);
                case REMINDER -> notificationPublisher.publishReminder(text);
                case RETURN -> notificationPublisher.publishInfo(text);
            })
            .timeout(PUBLISH_TIMEOUT)
            //the publisher logs what it published
            .doOnSuccess(published -> watch.told(notice))
            //the traceId of the pass goes with the notice
            .contextCapture()
            .subscribe(
                published -> {
                },
                e -> log.warn("The state of the presence detection could not be told: {}", e.toString())
            );
    }

    private String text(final Notice notice) {
        return switch (notice.kind()) {
            case ALERT -> "Presence detection not running: no pass evaluated since " + time(notice.since())
                + " (" + duration(notice.down()) + ") - " + notice.cause() + ". The last known presence is kept and"
                + " served, so every reader sees the house as it was." + hint(notice.cause());
            case REMINDER -> "Presence detection still not running since " + time(notice.since())
                + " (" + duration(notice.down()) + ") - " + notice.cause() + "." + hint(notice.cause());
            case RETURN -> "Presence detection running again after " + duration(notice.down())
                + " (down since " + time(notice.since()) + ")."
                + (notice.untold() ? " The alert could not be told while it lasted: " + notice.cause() + "." : "");
        };
    }

    //the gateway replaced its certificate: the fix is the pinned fingerprint
    private static String hint(final String cause) {
        return cause.endsWith(CERTIFICATE)
            ? " The gateway serves another certificate: set unifi.certificate-fingerprint (README of presence-service)."
            : "";
    }

    private String time(final Instant at) {
        return LocalDateTime.ofInstant(at, clock.getZone()).format(TIME);
    }

    static String duration(final Duration duration) {
        final long minutes = duration.toMinutes();
        return minutes < 60 ? minutes + " min" : minutes / 60 + " h " + minutes % 60 + " min";
    }
}
