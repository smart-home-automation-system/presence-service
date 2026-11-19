package cloud.cholewa.presence.service;

import cloud.cholewa.presence.service.PassWatch.Kind;
import cloud.cholewa.presence.service.PassWatch.Notice;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class PassWatchTest {

    private static final Instant T0 = Instant.parse("2026-11-17T00:00:00Z");
    private static final String CAUSE = "UniFi unreachable: CertificateException";

    private final PassWatch sut = new PassWatch(Duration.ofMinutes(10));

    @Test
    void should_say_nothing_while_the_passes_are_evaluated() {
        assertThat(sut.evaluated(T0)).isEmpty();
        assertThat(sut.evaluated(at(1))).isEmpty();
    }

    @Test
    void should_say_nothing_of_a_single_skipped_pass() {
        sut.evaluated(T0);

        assertThat(sut.skipped(at(1), CAUSE)).isEmpty();
        assertThat(sut.evaluated(at(2))).isEmpty();
    }

    @Test
    void should_alert_once_no_pass_was_evaluated_for_the_bound_counted_from_the_last_evaluated() {
        sut.evaluated(T0);
        for (int minute = 1; minute < 10; minute++) {
            assertThat(sut.skipped(at(minute), CAUSE)).isEmpty();
        }

        final Notice alert = sut.skipped(at(10), CAUSE).orElseThrow();

        assertThat(alert.kind()).isEqualTo(Kind.ALERT);
        assertThat(alert.since()).isEqualTo(T0);
        assertThat(alert.cause()).isEqualTo(CAUSE);
        assertThat(alert.down()).isEqualTo(Duration.ofMinutes(10));
    }

    //a restart inside an outage starts the count again: nothing evaluated yet, counted from the first skip
    @Test
    void should_count_from_the_first_skipped_pass_after_a_start() {
        assertThat(sut.skipped(T0, CAUSE)).isEmpty();
        assertThat(sut.skipped(at(9), CAUSE)).isEmpty();

        assertThat(sut.skipped(at(10), CAUSE)).map(Notice::since).contains(T0);
    }

    @Test
    void should_offer_the_alert_again_until_it_was_told() {
        sut.evaluated(T0);
        sut.skipped(at(10), CAUSE);

        assertThat(sut.skipped(at(11), CAUSE)).map(Notice::kind).contains(Kind.ALERT);
    }

    @Test
    void should_remind_every_hour_after_the_alert_was_told_with_the_latest_cause() {
        sut.evaluated(T0);
        sut.told(sut.skipped(at(10), CAUSE).orElseThrow());

        assertThat(sut.skipped(at(11), CAUSE)).isEmpty();
        assertThat(sut.skipped(at(69), CAUSE)).isEmpty();
        final Notice reminder = sut.skipped(at(70), "UniFi answered: 401").orElseThrow();
        assertThat(reminder.kind()).isEqualTo(Kind.REMINDER);
        assertThat(reminder.cause()).isEqualTo("UniFi answered: 401");

        sut.told(reminder);
        assertThat(sut.skipped(at(129), CAUSE)).isEmpty();
        assertThat(sut.skipped(at(130), CAUSE)).map(Notice::kind).contains(Kind.REMINDER);
    }

    @Test
    void should_tell_the_return_once_with_how_long_the_detection_was_down() {
        sut.evaluated(T0);
        sut.told(sut.skipped(at(10), CAUSE).orElseThrow());

        final Notice back = sut.evaluated(at(133)).orElseThrow();
        sut.told(back);

        assertThat(back.kind()).isEqualTo(Kind.RETURN);
        assertThat(back.down()).isEqualTo(Duration.ofMinutes(133));
        assertThat(back.untold()).isFalse();
        assertThat(sut.evaluated(at(134))).isEmpty();
    }

    //an alert that could not be published is announced with the return
    @Test
    void should_announce_an_untold_alert_with_the_return() {
        sut.evaluated(T0);
        sut.skipped(at(10), CAUSE);

        final Optional<Notice> back = sut.evaluated(at(12));

        assertThat(back).map(Notice::untold).contains(true);
        assertThat(back).map(Notice::cause).contains(CAUSE);
    }

    @Test
    void should_offer_a_return_that_did_not_get_through_again_until_it_did() {
        sut.evaluated(T0);
        sut.told(sut.skipped(at(10), CAUSE).orElseThrow());
        final Notice back = sut.evaluated(at(11)).orElseThrow();

        assertThat(sut.evaluated(at(12))).contains(back);
        sut.told(back);
        assertThat(sut.evaluated(at(13))).isEmpty();
    }

    //a new outage replaces a return never told - its alert says more
    @Test
    void should_drop_an_untold_return_when_a_new_outage_begins() {
        sut.evaluated(T0);
        sut.told(sut.skipped(at(10), CAUSE).orElseThrow());
        sut.evaluated(at(11));

        sut.skipped(at(12), CAUSE);

        assertThat(sut.evaluated(at(13))).isEmpty();
    }

    @Test
    void should_start_a_new_episode_after_a_return() {
        sut.evaluated(T0);
        sut.told(sut.skipped(at(10), CAUSE).orElseThrow());
        sut.evaluated(at(11));

        assertThat(sut.skipped(at(12), CAUSE)).isEmpty();
        assertThat(sut.skipped(at(21), CAUSE)).map(Notice::kind).contains(Kind.ALERT);
    }

    //an alert of an episode already over, confirmed late, does not silence the next one
    @Test
    void should_ignore_a_notice_of_an_episode_already_over() {
        sut.evaluated(T0);
        final Notice late = sut.skipped(at(10), CAUSE).orElseThrow();
        sut.evaluated(at(11));
        sut.skipped(at(12), CAUSE);

        sut.told(late);

        assertThat(sut.skipped(at(21), CAUSE)).map(Notice::kind).contains(Kind.ALERT);
    }

    private static Instant at(final int minutes) {
        return T0.plus(Duration.ofMinutes(minutes));
    }
}
