package cloud.cholewa.presence.service;

import cloud.cholewa.presence.config.PresenceProperties;
import cloud.cholewa.presence.model.Member;
import cloud.cholewa.presence.model.PresenceDecision;
import cloud.cholewa.presence.model.PresenceDecision.Changed;
import cloud.cholewa.presence.model.PresenceDecision.Confirmed;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static cloud.cholewa.presence.model.PresenceStatus.ABSENT;
import static cloud.cholewa.presence.model.PresenceStatus.PRESENT;
import static org.assertj.core.api.Assertions.assertThat;

class PresenceTrackerTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 2, 8, 0);
    private static final String PHONE = "02:00:00:00:00:01";
    private static final String WATCH = "02:00:00:00:00:02";
    private static final Member ANNA = new Member("Anna", Set.of(PHONE, WATCH));
    private static final Set<String> NOBODY = Set.of();

    private PresenceTracker sut;

    @BeforeEach
    void setUp() {
        sut = new PresenceTracker(new PresenceProperties(Duration.ofMinutes(10)));
    }

    @Test
    void should_open_present_on_first_sighting() {
        assertThat(pass(T0, Set.of(PHONE))).isEqualTo(new Changed("Anna", PRESENT, T0, null, T0));
    }

    @Test
    void should_open_absent_for_a_member_never_seen() {
        assertThat(pass(T0, NOBODY)).isEqualTo(new Changed("Anna", ABSENT, T0, null, T0));
    }

    @Test
    void should_be_present_when_any_device_is_seen() {
        passAndCommit(T0, Set.of(PHONE));

        assertThat(pass(T0.plusMinutes(1), Set.of(WATCH, "02:00:00:00:00:99")))
            .isEqualTo(new Confirmed("Anna", PRESENT, T0.plusMinutes(1)));
    }

    @Test
    void should_keep_present_during_the_grace_period() {
        passAndCommit(T0, Set.of(PHONE));

        //first missed at T0+1, so the grace period runs until T0+11
        assertThat(pass(T0.plusMinutes(1), NOBODY)).isEqualTo(new Confirmed("Anna", PRESENT, T0.plusMinutes(1)));
        assertThat(pass(T0.plusMinutes(10), NOBODY)).isEqualTo(new Confirmed("Anna", PRESENT, T0.plusMinutes(10)));
    }

    @Test
    void should_turn_absent_after_the_grace_period_starting_at_the_last_sighting() {
        passAndCommit(T0, Set.of(PHONE));
        pass(T0.plusMinutes(1), NOBODY);

        //the absence starts when the member was last seen and the PRESENT row ends there too
        assertThat(pass(T0.plusMinutes(11), NOBODY))
            .isEqualTo(new Changed("Anna", ABSENT, T0, T0, T0.plusMinutes(11)));
    }

    @Test
    void should_stay_present_when_the_device_flaps_within_the_grace_period() {
        passAndCommit(T0, Set.of(PHONE));
        pass(T0.plusMinutes(1), NOBODY);
        pass(T0.plusMinutes(9), NOBODY);

        //back before the grace period ran out - and the next disappearance starts a new one
        assertThat(pass(T0.plusMinutes(10), Set.of(PHONE)))
            .isEqualTo(new Confirmed("Anna", PRESENT, T0.plusMinutes(10)));
        assertThat(pass(T0.plusMinutes(11), NOBODY)).isInstanceOf(Confirmed.class);
        assertThat(pass(T0.plusMinutes(20), NOBODY)).isInstanceOf(Confirmed.class);
        assertThat(pass(T0.plusMinutes(21), NOBODY))
            .isEqualTo(new Changed("Anna", ABSENT, T0.plusMinutes(10), T0.plusMinutes(10), T0.plusMinutes(21)));
    }

    @Test
    void should_return_to_present_after_an_absence() {
        passAndCommit(T0, Set.of(PHONE));
        pass(T0.plusMinutes(1), NOBODY);
        passAndCommit(T0.plusMinutes(11), NOBODY);

        assertThat(pass(T0.plusMinutes(12), NOBODY)).isEqualTo(new Confirmed("Anna", ABSENT, T0.plusMinutes(12)));
        assertThat(pass(T0.plusHours(2), Set.of(PHONE)))
            .isEqualTo(new Changed("Anna", PRESENT, T0.plusHours(2), null, T0.plusHours(2)));
    }

    //passes skipped while the gateway was down are not observations: the member was last seen an
    //hour ago, but the first pass after the outage only starts the grace period
    @Test
    void should_not_turn_absent_on_the_first_pass_after_skipped_passes() {
        passAndCommit(T0, Set.of(PHONE));

        assertThat(pass(T0.plusHours(1), NOBODY)).isEqualTo(new Confirmed("Anna", PRESENT, T0.plusHours(1)));
        assertThat(pass(T0.plusHours(1).plusMinutes(10), NOBODY))
            .isEqualTo(new Changed("Anna", ABSENT, T0, T0, T0.plusHours(1).plusMinutes(10)));
    }

    //the status moves only when the write is confirmed, so a failed write is decided again
    @Test
    void should_repeat_the_decision_until_it_is_committed() {
        assertThat(pass(T0, Set.of(PHONE))).isInstanceOf(Changed.class);

        final PresenceDecision repeated = pass(T0.plusMinutes(1), Set.of(PHONE));
        assertThat(repeated).isEqualTo(new Changed("Anna", PRESENT, T0.plusMinutes(1), null, T0.plusMinutes(1)));

        sut.commit(repeated);
        assertThat(pass(T0.plusMinutes(2), Set.of(PHONE))).isInstanceOf(Confirmed.class);
    }

    @Test
    void should_confirm_the_restored_status_instead_of_opening_a_new_row() {
        sut.restore("Anna", PRESENT, T0);
        assertThat(pass(T0.plusMinutes(5), Set.of(PHONE))).isEqualTo(new Confirmed("Anna", PRESENT, T0.plusMinutes(5)));

        sut.restore("Anna", ABSENT, T0);
        assertThat(pass(T0.plusMinutes(5), NOBODY)).isEqualTo(new Confirmed("Anna", ABSENT, T0.plusMinutes(5)));
    }

    //a restart alone never turns anyone absent: the grace period starts with the first pass after it,
    //and the absence then starts at the last check stored before the restart
    @Test
    void should_start_the_grace_period_after_a_restart_for_a_restored_present_member() {
        sut.restore("Anna", PRESENT, T0);

        assertThat(pass(T0.plusHours(3), NOBODY)).isEqualTo(new Confirmed("Anna", PRESENT, T0.plusHours(3)));
        assertThat(pass(T0.plusHours(3).plusMinutes(10), NOBODY))
            .isEqualTo(new Changed("Anna", ABSENT, T0, T0, T0.plusHours(3).plusMinutes(10)));
    }

    @Test
    void should_decide_for_every_member_independently() {
        final Member jan = new Member("Jan", Set.of("02:00:00:00:00:03"));

        final List<PresenceDecision> decisions = sut.evaluate(T0, List.of(ANNA, jan), Set.of(PHONE));

        assertThat(decisions).containsExactly(
            new Changed("Anna", PRESENT, T0, null, T0),
            new Changed("Jan", ABSENT, T0, null, T0)
        );
    }

    @Test
    void should_treat_a_member_without_devices_as_absent() {
        final Member noDevices = new Member("Ola", Set.of());

        assertThat(sut.evaluate(T0, List.of(noDevices), Set.of(PHONE)))
            .containsExactly(new Changed("Ola", ABSENT, T0, null, T0));
    }

    private PresenceDecision pass(final LocalDateTime now, final Set<String> connected) {
        return sut.evaluate(now, List.of(ANNA), connected).getFirst();
    }

    private void passAndCommit(final LocalDateTime now, final Set<String> connected) {
        sut.commit(pass(now, connected));
    }
}
