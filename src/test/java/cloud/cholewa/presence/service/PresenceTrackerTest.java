package cloud.cholewa.presence.service;

import cloud.cholewa.presence.config.PresenceProperties;
import cloud.cholewa.presence.model.Member;
import cloud.cholewa.presence.model.PresenceDecision;
import cloud.cholewa.presence.model.PresenceDecision.Changed;
import cloud.cholewa.presence.model.PresenceDecision.Confirmed;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;

import static cloud.cholewa.presence.model.PresenceStatus.ABSENT;
import static cloud.cholewa.presence.model.PresenceStatus.PRESENT;
import static org.assertj.core.api.Assertions.assertThat;

class PresenceTrackerTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Warsaw");
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 2, 8, 0);
    private static final String PHONE = "02:00:00:00:00:01";
    private static final String WATCH = "02:00:00:00:00:02";
    private static final Member ANNA = new Member("Anna", Set.of(PHONE, WATCH));
    private static final Member JAN = new Member("Jan", Set.of("02:00:00:00:00:03"));
    private static final Set<String> NOBODY = Set.of();

    private PresenceTracker sut;

    @BeforeEach
    void setUp() {
        //only the zone of the clock is used - the time of a pass is always passed in
        sut = new PresenceTracker(
            new PresenceProperties(Duration.ofMinutes(10), Duration.ofDays(365)), Clock.fixed(Instant.EPOCH, ZONE));
    }

    @Test
    void should_open_present_on_first_sighting() {
        assertThat(pass(T0, Set.of(PHONE))).containsExactly(new Changed("Anna", PRESENT, T0, T0));
    }

    @Test
    void should_open_absent_for_a_member_never_seen() {
        assertThat(pass(T0, NOBODY)).containsExactly(new Changed("Anna", ABSENT, T0, T0));
    }

    @Test
    void should_be_present_when_any_device_is_seen() {
        passAndCommit(T0, Set.of(PHONE));

        assertThat(pass(T0.plusMinutes(1), Set.of(WATCH, "02:00:00:00:00:99")))
            .containsExactly(new Confirmed("Anna", PRESENT, T0.plusMinutes(1)));
    }

    //nothing is written while waiting: the PRESENT row keeps the last sighting as its last check
    @Test
    void should_decide_nothing_during_the_grace_period() {
        passAndCommit(T0, Set.of(PHONE));

        //first missed at T0+1, so the grace period runs until T0+11
        assertThat(pass(T0.plusMinutes(1), NOBODY)).isEmpty();
        assertThat(missedEveryMinute(2, 10)).isEmpty();
    }

    @Test
    void should_turn_absent_after_the_grace_period_starting_at_the_last_sighting() {
        passAndCommit(T0, Set.of(PHONE));

        assertThat(missedEveryMinute(1, 11))
            .containsExactly(new Changed("Anna", ABSENT, T0, T0.plusMinutes(11)));
    }

    @Test
    void should_stay_present_when_the_device_flaps_within_the_grace_period() {
        passAndCommit(T0, Set.of(PHONE));
        missedEveryMinute(1, 9);

        //back before the grace period ran out - and the next disappearance starts a new one
        assertThat(pass(T0.plusMinutes(10), Set.of(PHONE)))
            .containsExactly(new Confirmed("Anna", PRESENT, T0.plusMinutes(10)));
        assertThat(missedEveryMinute(11, 20)).isEmpty();
        assertThat(pass(T0.plusMinutes(21), NOBODY))
            .containsExactly(new Changed("Anna", ABSENT, T0.plusMinutes(10), T0.plusMinutes(21)));
    }

    @Test
    void should_return_to_present_after_an_absence() {
        passAndCommit(T0, Set.of(PHONE));
        missedEveryMinute(1, 10);
        passAndCommit(T0.plusMinutes(11), NOBODY);

        assertThat(pass(T0.plusMinutes(12), NOBODY)).containsExactly(new Confirmed("Anna", ABSENT, T0.plusMinutes(12)));
        assertThat(pass(T0.plusHours(2), Set.of(PHONE)))
            .containsExactly(new Changed("Anna", PRESENT, T0.plusHours(2), T0.plusHours(2)));
    }

    //passes skipped while the gateway was down are not observations: the member was last seen an
    //hour ago, but the first pass after the outage only starts the grace period
    @Test
    void should_not_turn_absent_on_the_first_pass_after_skipped_passes() {
        passAndCommit(T0, Set.of(PHONE));

        assertThat(pass(T0.plusMinutes(60), NOBODY)).isEmpty();
        assertThat(missedEveryMinute(61, 69)).isEmpty();
        assertThat(pass(T0.plusMinutes(70), NOBODY))
            .containsExactly(new Changed("Anna", ABSENT, T0, T0.plusMinutes(70)));
    }

    //the same outage, but it begins after the member was already missed once: the minute before it
    //and the first pass after it are two observations, not a grace period
    @Test
    void should_start_the_grace_period_over_when_passes_were_skipped_inside_it() {
        passAndCommit(T0, Set.of(PHONE));
        pass(T0.plusMinutes(1), NOBODY);

        //nothing ran between T0+1 and T0+30
        assertThat(pass(T0.plusMinutes(30), NOBODY)).isEmpty();
        assertThat(missedEveryMinute(31, 39)).isEmpty();
        assertThat(pass(T0.plusMinutes(40), NOBODY))
            .containsExactly(new Changed("Anna", ABSENT, T0, T0.plusMinutes(40)));
    }

    //one skipped pass is not an outage
    @Test
    void should_keep_the_grace_period_running_over_a_single_skipped_pass() {
        passAndCommit(T0, Set.of(PHONE));
        missedEveryMinute(1, 4);
        //the pass at T0+5 was skipped
        missedEveryMinute(6, 10);

        assertThat(pass(T0.plusMinutes(11), NOBODY))
            .containsExactly(new Changed("Anna", ABSENT, T0, T0.plusMinutes(11)));
    }

    //the status moves only when the write is confirmed, so a failed write is decided again
    @Test
    void should_repeat_the_decision_until_it_is_committed() {
        assertThat(pass(T0, Set.of(PHONE))).hasOnlyElementsOfType(Changed.class);

        final List<PresenceDecision> repeated = pass(T0.plusMinutes(1), Set.of(PHONE));
        assertThat(repeated).containsExactly(new Changed("Anna", PRESENT, T0.plusMinutes(1), T0.plusMinutes(1)));

        sut.commit(repeated.getFirst());
        assertThat(pass(T0.plusMinutes(2), Set.of(PHONE))).hasOnlyElementsOfType(Confirmed.class);
    }

    @Test
    void should_confirm_the_restored_status_instead_of_opening_a_new_row() {
        sut.restore("Anna", PRESENT, T0);
        assertThat(pass(T0.plusMinutes(5), Set.of(PHONE)))
            .containsExactly(new Confirmed("Anna", PRESENT, T0.plusMinutes(5)));

        sut.restore("Anna", ABSENT, T0);
        assertThat(pass(T0.plusMinutes(5), NOBODY)).containsExactly(new Confirmed("Anna", ABSENT, T0.plusMinutes(5)));
    }

    //a restart alone never turns anyone absent: the grace period starts with the first pass after it,
    //and the absence then starts at the last sighting stored before the restart
    @Test
    void should_start_the_grace_period_after_a_restart_for_a_restored_present_member() {
        sut.restore("Anna", PRESENT, T0);

        assertThat(pass(T0.plusMinutes(180), NOBODY)).isEmpty();
        assertThat(missedEveryMinute(181, 189)).isEmpty();
        assertThat(pass(T0.plusMinutes(190), NOBODY))
            .containsExactly(new Changed("Anna", ABSENT, T0, T0.plusMinutes(190)));
    }

    @Test
    void should_decide_for_every_member_independently() {
        assertThat(sut.evaluate(instant(T0), List.of(ANNA, JAN), Set.of(PHONE))).containsExactly(
            new Changed("Anna", PRESENT, T0, T0),
            new Changed("Jan", ABSENT, T0, T0)
        );
    }

    @Test
    void should_treat_a_member_without_devices_as_absent() {
        assertThat(sut.evaluate(instant(T0), List.of(new Member("Ola", Set.of())), Set.of(PHONE)))
            .containsExactly(new Changed("Ola", ABSENT, T0, T0));
    }

    //a member removed from the registry (or deactivated) is no longer watched; when they come back
    //their history continues with a new row instead of a grace period on a stale sighting
    @Test
    void should_forget_a_member_that_left_the_registry() {
        passAndCommit(T0, Set.of(PHONE));

        assertThat(sut.evaluate(instant(T0.plusMinutes(1)), List.of(JAN), NOBODY))
            .containsExactly(new Changed("Jan", ABSENT, T0.plusMinutes(1), T0.plusMinutes(1)));

        assertThat(pass(T0.plusDays(3), NOBODY))
            .containsExactly(new Changed("Anna", ABSENT, T0.plusDays(3), T0.plusDays(3)));
    }

    //2026-10-25 03:00 CEST becomes 02:00 CET: the wall clock runs 02:50 -> 02:05, real time moves
    //15 minutes forward. The grace period is a duration, so it must not be thrown off either way.
    @Test
    void should_measure_the_grace_period_in_real_time_when_the_clocks_go_back() {
        final ZonedDateTime before = ZonedDateTime.of(2026, 10, 25, 2, 50, 0, 0, ZONE).withEarlierOffsetAtOverlap();
        sut.evaluate(before.toInstant(), List.of(ANNA), Set.of(PHONE)).forEach(sut::commit);

        //first missed at 02:55 CEST; 02:04 CET on the wall clock is 14 real minutes after the sighting
        for (int minute = 5; minute <= 14; minute++) {
            assertThat(sut.evaluate(before.plusMinutes(minute).toInstant(), List.of(ANNA), NOBODY)).isEmpty();
        }

        final List<PresenceDecision> decisions = sut.evaluate(before.plusMinutes(15).toInstant(), List.of(ANNA), NOBODY);

        assertThat(decisions).containsExactly(new Changed(
            "Anna", ABSENT, LocalDateTime.of(2026, 10, 25, 2, 50), LocalDateTime.of(2026, 10, 25, 2, 5)));
    }

    //2026-03-29 02:00 CET becomes 03:00 CEST: 01:55 -> 03:01 on the wall clock is 6 real minutes
    @Test
    void should_not_cut_the_grace_period_short_when_the_clocks_go_forward() {
        final ZonedDateTime seen = ZonedDateTime.of(2026, 3, 29, 1, 54, 0, 0, ZONE);
        sut.evaluate(seen.toInstant(), List.of(ANNA), Set.of(PHONE)).forEach(sut::commit);

        //first missed at 01:55; by 03:04 on the wall clock only 10 real minutes have passed
        for (int minute = 1; minute <= 10; minute++) {
            assertThat(sut.evaluate(seen.plusMinutes(minute).toInstant(), List.of(ANNA), NOBODY)).isEmpty();
        }
        assertThat(sut.evaluate(seen.plusMinutes(11).toInstant(), List.of(ANNA), NOBODY))
            .hasOnlyElementsOfType(Changed.class);
    }

    //what the house report asks: who counts as at home right now. Anna is, also while she is not
    //seen and her grace period runs; she is not before the write was committed, after she was
    //decided absent, or once she has left the registry
    @Test
    void should_name_the_members_considered_present_right_now() {
        assertThat(sut.presentMembers()).isEmpty();

        final List<PresenceDecision> arrived = pass(T0, Set.of(PHONE));
        assertThat(sut.presentMembers()).isEmpty();

        arrived.forEach(sut::commit);
        missedEveryMinute(1, 9);
        assertThat(sut.presentMembers()).containsExactly("Anna");

        missedEveryMinute(10, 11).forEach(sut::commit);
        assertThat(sut.presentMembers()).isEmpty();

        passAndCommit(T0.plusMinutes(12), Set.of(PHONE));
        assertThat(sut.presentMembers()).containsExactly("Anna");

        sut.evaluate(instant(T0.plusMinutes(13)), List.of(JAN), NOBODY);
        assertThat(sut.presentMembers()).isEmpty();
    }

    //one pass a minute without any device, from T0+fromMinute to T0+toMinute; answers the last one
    private List<PresenceDecision> missedEveryMinute(final int fromMinute, final int toMinute) {
        List<PresenceDecision> last = List.of();
        for (int minute = fromMinute; minute <= toMinute; minute++) {
            last = pass(T0.plusMinutes(minute), NOBODY);
        }
        return last;
    }

    private List<PresenceDecision> pass(final LocalDateTime now, final Set<String> connected) {
        return sut.evaluate(instant(now), List.of(ANNA), connected);
    }

    private void passAndCommit(final LocalDateTime now, final Set<String> connected) {
        pass(now, connected).forEach(sut::commit);
    }

    private static Instant instant(final LocalDateTime localDateTime) {
        return localDateTime.atZone(ZONE).toInstant();
    }
}
