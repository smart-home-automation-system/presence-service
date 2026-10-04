package cloud.cholewa.presence.service;

import cloud.cholewa.presence.database.model.PresenceStatusEntity;
import cloud.cholewa.presence.model.PresenceInterval;
import cloud.cholewa.presence.model.PresenceStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static cloud.cholewa.presence.model.PresenceStatus.ABSENT;
import static cloud.cholewa.presence.model.PresenceStatus.PRESENT;
import static org.assertj.core.api.Assertions.assertThat;

class PresenceIntervalCalculatorTest {

    private static final LocalDateTime DAY = LocalDateTime.of(2026, 10, 2, 0, 0);
    private static final LocalDateTime FROM = DAY.withHour(8);
    private static final LocalDateTime TO = DAY.withHour(20);

    private final PresenceIntervalCalculator sut = new PresenceIntervalCalculator();

    @Test
    void should_report_a_period_inside_the_range_as_it_is() {
        final List<PresenceInterval> intervals = sut.derive(
            List.of(row(1, PRESENT, 10, 12), row(2, ABSENT, 12, 13)), 2L, FROM, TO);

        assertThat(intervals).containsExactly(new PresenceInterval(DAY.withHour(10), DAY.withHour(12), false));
    }

    @Test
    void should_clip_a_period_that_started_before_the_range() {
        final List<PresenceInterval> intervals = sut.derive(
            List.of(row(1, PRESENT, 6, 10), row(2, ABSENT, 10, 21)), 2L, FROM, TO);

        assertThat(intervals).containsExactly(new PresenceInterval(FROM, DAY.withHour(10), false));
    }

    @Test
    void should_clip_a_period_that_ends_after_the_range() {
        final List<PresenceInterval> intervals = sut.derive(
            List.of(row(1, PRESENT, 18, 22), row(2, ABSENT, 22, 23)), 2L, FROM, TO);

        assertThat(intervals).containsExactly(new PresenceInterval(DAY.withHour(18), TO, false));
    }

    @Test
    void should_clip_a_period_that_covers_the_whole_range_on_both_sides() {
        final List<PresenceInterval> intervals = sut.derive(
            List.of(row(1, PRESENT, 6, 22), row(2, ABSENT, 22, 23)), 2L, FROM, TO);

        assertThat(intervals).containsExactly(new PresenceInterval(FROM, TO, false));
    }

    //the row still going on: its end is the last check, and the flag says it is not a departure
    @Test
    void should_end_the_open_row_at_its_last_check_and_mark_it_open() {
        final List<PresenceInterval> intervals = sut.derive(
            List.of(row(1, PRESENT, 9, 10), row(3, PRESENT, 15, 17)), 3L, FROM, TO);

        assertThat(intervals).containsExactly(
            new PresenceInterval(DAY.withHour(9), DAY.withHour(10), false),
            new PresenceInterval(DAY.withHour(15), DAY.withHour(17), true)
        );
    }

    //the range ends before the last check: the end is the edge of the range, which says nothing
    //about the period still going on
    @Test
    void should_not_mark_the_open_row_open_when_the_range_cuts_its_end_off() {
        final List<PresenceInterval> intervals = sut.derive(List.of(row(3, PRESENT, 15, 22)), 3L, FROM, TO);

        assertThat(intervals).containsExactly(new PresenceInterval(DAY.withHour(15), TO, false));
    }

    //the latest row of the member is an absence, so no PRESENT row is open - also not the last one
    @Test
    void should_not_mark_a_closed_period_open() {
        final List<PresenceInterval> intervals = sut.derive(List.of(row(1, PRESENT, 9, 10)), 2L, FROM, TO);

        assertThat(intervals).containsExactly(new PresenceInterval(DAY.withHour(9), DAY.withHour(10), false));
    }

    //the service was down between 11 and 16: the absence was last checked at 11 and the next
    //presence starts at 16. Nothing is invented for the time nobody was watching
    @Test
    void should_leave_a_downtime_gap_as_it_is() {
        final List<PresenceInterval> intervals = sut.derive(
            List.of(row(1, PRESENT, 9, 10), row(2, ABSENT, 10, 11), row(3, PRESENT, 16, 18)), 3L, FROM, TO);

        assertThat(intervals).containsExactly(
            new PresenceInterval(DAY.withHour(9), DAY.withHour(10), false),
            new PresenceInterval(DAY.withHour(16), DAY.withHour(18), true)
        );
    }

    @Test
    void should_leave_out_periods_outside_the_range() {
        final List<PresenceInterval> intervals = sut.derive(
            List.of(row(1, PRESENT, 5, 7), row(2, ABSENT, 7, 21), row(3, PRESENT, 21, 23)), 3L, FROM, TO);

        assertThat(intervals).isEmpty();
    }

    //the range is closed at its start and open at its end: a period touching it only with its
    //edge belongs to the range next to it, and would otherwise be counted by both
    @Test
    void should_drop_a_period_ending_exactly_at_the_start_and_one_starting_exactly_at_the_end() {
        final List<PresenceInterval> intervals = sut.derive(
            List.of(row(1, PRESENT, 6, 8), row(2, ABSENT, 8, 20), row(3, PRESENT, 20, 22)), 3L, FROM, TO);

        assertThat(intervals).isEmpty();
    }

    @Test
    void should_keep_a_period_starting_exactly_at_the_start_also_one_of_a_single_pass() {
        assertThat(sut.derive(List.of(row(1, PRESENT, 8, 9), row(2, ABSENT, 9, 10)), 2L, FROM, TO))
            .containsExactly(new PresenceInterval(FROM, DAY.withHour(9), false));
        assertThat(sut.derive(List.of(row(1, PRESENT, 8, 8), row(2, ABSENT, 8, 10)), 2L, FROM, TO))
            .containsExactly(new PresenceInterval(FROM, FROM, false));
    }

    //last checked exactly at the end of the range: the end is the edge, like for any later check
    @Test
    void should_not_mark_the_open_row_open_when_it_was_last_checked_exactly_at_the_end() {
        final List<PresenceInterval> intervals = sut.derive(List.of(row(3, PRESENT, 15, 20)), 3L, FROM, TO);

        assertThat(intervals).containsExactly(new PresenceInterval(DAY.withHour(15), TO, false));
    }

    //the query always answers the newest row of the member, also an absence or a presence outside
    //the range - it only says which row is the newest
    @Test
    void should_ignore_the_newest_row_when_it_is_not_a_presence_in_the_range() {
        assertThat(sut.derive(List.of(row(1, PRESENT, 9, 10), row(2, ABSENT, 10, 23)), 2L, FROM, TO))
            .containsExactly(new PresenceInterval(DAY.withHour(9), DAY.withHour(10), false));
        assertThat(sut.derive(List.of(row(1, PRESENT, 9, 10), row(3, PRESENT, 21, 23)), 3L, FROM, TO))
            .containsExactly(new PresenceInterval(DAY.withHour(9), DAY.withHour(10), false));
    }

    //a member seen by a single pass: the row starts and was last checked at the same moment
    @Test
    void should_report_a_period_of_a_single_pass() {
        final List<PresenceInterval> intervals = sut.derive(List.of(row(1, PRESENT, 12, 12)), 1L, FROM, TO);

        assertThat(intervals).containsExactly(new PresenceInterval(DAY.withHour(12), DAY.withHour(12), true));
    }

    @Test
    void should_order_the_periods_by_the_order_they_were_stored_in() {
        final List<PresenceInterval> intervals = sut.derive(
            List.of(row(3, PRESENT, 15, 17), row(1, PRESENT, 9, 10)), 3L, FROM, TO);

        assertThat(intervals).extracting(PresenceInterval::from)
            .containsExactly(DAY.withHour(9), DAY.withHour(15));
    }

    @Test
    void should_answer_no_period_for_a_member_without_rows() {
        assertThat(sut.derive(List.of(), null, FROM, TO)).isEmpty();
    }

    private static PresenceStatusEntity row(
        final long id,
        final PresenceStatus status,
        final int startedHour,
        final int lastCheckedHour
    ) {
        return new PresenceStatusEntity(id, "Anna", status, DAY.withHour(startedHour), DAY.withHour(lastCheckedHour));
    }
}
