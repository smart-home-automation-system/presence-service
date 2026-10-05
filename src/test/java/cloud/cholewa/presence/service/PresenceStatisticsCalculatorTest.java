package cloud.cholewa.presence.service;

import cloud.cholewa.presence.model.DailyOccupancy;
import cloud.cholewa.presence.model.DailyPresence;
import cloud.cholewa.presence.model.OccupancyInterval;
import cloud.cholewa.presence.model.PresenceInterval;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PresenceStatisticsCalculatorTest {

    private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");
    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 5);
    private static final LocalDate TUESDAY = MONDAY.plusDays(1);
    private static final LocalDate WEDNESDAY = MONDAY.plusDays(2);
    private static final long HOUR = 3600;
    private static final long DAY = 24 * HOUR;

    private final PresenceStatisticsCalculator sut =
        new PresenceStatisticsCalculator(Clock.fixed(Instant.parse("2026-10-07T10:00:00Z"), WARSAW));

    @Test
    void should_sum_the_time_at_home_of_a_day_with_its_first_arrival_and_last_departure() {
        final LocalDateTime from = MONDAY.atStartOfDay();
        final LocalDateTime to = TUESDAY.atStartOfDay();

        final List<DailyPresence> days = sut.dailyPresence(
            List.of(closed(MONDAY.atTime(7, 0), MONDAY.atTime(9, 0)), closed(MONDAY.atTime(17, 0), MONDAY.atTime(23, 0))),
            from, to, to);

        assertThat(days).containsExactly(new DailyPresence(
            MONDAY, 8 * HOUR, MONDAY.atTime(7, 0), MONDAY.atTime(23, 0), 33.3));
    }

    //a night at home: the evening belongs to Monday, the morning to Tuesday. Midnight is neither a
    //departure on Monday nor an arrival on Tuesday
    @Test
    void should_split_a_presence_crossing_midnight_between_the_two_days() {
        final LocalDateTime from = MONDAY.atStartOfDay();
        final LocalDateTime to = WEDNESDAY.atStartOfDay();

        final List<DailyPresence> days = sut.dailyPresence(
            List.of(closed(MONDAY.atTime(18, 0), TUESDAY.atTime(8, 0))), from, to, to);

        assertThat(days).containsExactly(
            new DailyPresence(MONDAY, 6 * HOUR, MONDAY.atTime(18, 0), null, 25.0),
            new DailyPresence(TUESDAY, 8 * HOUR, null, TUESDAY.atTime(8, 0), 33.3)
        );
    }

    @Test
    void should_report_a_day_spent_away_with_nothing_but_zeros() {
        final LocalDateTime from = MONDAY.atStartOfDay();
        final LocalDateTime to = WEDNESDAY.atStartOfDay();

        final List<DailyPresence> days = sut.dailyPresence(
            List.of(closed(TUESDAY.atTime(10, 0), TUESDAY.atTime(11, 0))), from, to, to);

        assertThat(days).first().isEqualTo(new DailyPresence(MONDAY, 0, null, null, 0.0));
    }

    //at home all day: nothing arrived and nothing left, and both edges of the range cut the interval
    @Test
    void should_report_a_whole_day_at_home_without_an_arrival_or_a_departure() {
        final LocalDateTime from = MONDAY.atStartOfDay();
        final LocalDateTime to = TUESDAY.atStartOfDay();

        final List<DailyPresence> days = sut.dailyPresence(List.of(closed(from, to)), from, to, to);

        assertThat(days).containsExactly(new DailyPresence(MONDAY, DAY, null, null, 100.0));
    }

    //the presence still going on has not ended, so it is no departure; and the day is only as long
    //as it was observed - 6 of the 12 hours watched, not of 24
    @Test
    void should_end_the_running_day_at_the_last_check_and_count_no_departure_for_an_open_presence() {
        final LocalDateTime from = MONDAY.atStartOfDay();
        final LocalDateTime to = TUESDAY.atStartOfDay();
        final LocalDateTime lastCheck = MONDAY.atTime(12, 0);

        final List<DailyPresence> days = sut.dailyPresence(
            List.of(new PresenceInterval(MONDAY.atTime(6, 0), lastCheck, true)), from, to, lastCheck);

        assertThat(days).containsExactly(new DailyPresence(MONDAY, 6 * HOUR, MONDAY.atTime(6, 0), null, 50.0));
    }

    //a range that starts and ends inside a day: both days are cut to it
    @Test
    void should_cut_the_first_and_the_last_day_to_the_range() {
        final LocalDateTime from = MONDAY.atTime(12, 0);
        final LocalDateTime to = TUESDAY.atTime(6, 0);

        final List<DailyPresence> days = sut.dailyPresence(
            List.of(closed(MONDAY.atTime(18, 0), TUESDAY.atTime(3, 0))), from, to, to);

        assertThat(days).containsExactly(
            new DailyPresence(MONDAY, 6 * HOUR, MONDAY.atTime(18, 0), null, 50.0),
            new DailyPresence(TUESDAY, 3 * HOUR, null, TUESDAY.atTime(3, 0), 50.0)
        );
    }

    @Test
    void should_answer_no_day_when_nothing_was_observed_in_the_range() {
        final LocalDateTime from = MONDAY.atStartOfDay();

        assertThat(sut.dailyPresence(List.of(), from, TUESDAY.atStartOfDay(), from)).isEmpty();
    }

    //the clocks go back on 2026-10-25: the day has 25 hours, and a presence across the change is as
    //long as it really was
    @Test
    void should_measure_the_day_the_clocks_go_back_as_25_hours() {
        final LocalDate changeDay = LocalDate.of(2026, 10, 25);
        final LocalDateTime from = changeDay.atStartOfDay();
        final LocalDateTime to = changeDay.plusDays(1).atStartOfDay();

        final List<DailyPresence> days = sut.dailyPresence(
            List.of(closed(changeDay.atTime(1, 0), changeDay.atTime(4, 0))), from, to, to);

        assertThat(days).containsExactly(new DailyPresence(
            changeDay, 4 * HOUR, changeDay.atTime(1, 0), changeDay.atTime(4, 0), 16.0));
    }

    @Test
    void should_measure_the_day_the_clocks_go_forward_as_23_hours() {
        final LocalDate changeDay = LocalDate.of(2027, 3, 28);
        final LocalDateTime from = changeDay.atStartOfDay();
        final LocalDateTime to = changeDay.plusDays(1).atStartOfDay();

        final List<DailyPresence> days = sut.dailyPresence(List.of(closed(from, to)), from, to, to);

        assertThat(days).containsExactly(new DailyPresence(changeDay, 23 * HOUR, null, null, 100.0));
    }

    //on the night the clocks go back a row can be stored with its last check before its start, and
    //two rows can overlap in local time: nothing is counted backwards and nothing twice
    @Test
    void should_ignore_an_interval_running_backwards_and_count_overlapping_ones_once() {
        final LocalDate changeDay = LocalDate.of(2026, 10, 25);
        final LocalDateTime from = changeDay.atStartOfDay();
        final LocalDateTime to = changeDay.plusDays(1).atStartOfDay();

        final List<DailyPresence> days = sut.dailyPresence(
            List.of(
                closed(changeDay.atTime(2, 50), changeDay.atTime(2, 5)),
                closed(changeDay.atTime(10, 0), changeDay.atTime(12, 0)),
                closed(changeDay.atTime(11, 0), changeDay.atTime(13, 0))
            ),
            from, to, to);

        assertThat(days).singleElement().satisfies(day -> {
            assertThat(day.secondsAtHome()).isEqualTo(3 * HOUR);
            assertThat(day.firstArrival()).isEqualTo(changeDay.atTime(10, 0));
        });
    }

    //Anna 8-12, Tom 10-14 and again 20-22: occupied 8-14 and 20-22, empty before, between and after
    @Test
    void should_unite_the_presence_of_everyone_into_one_timeline() {
        final LocalDateTime from = MONDAY.atStartOfDay();
        final LocalDateTime to = TUESDAY.atStartOfDay();

        final List<OccupancyInterval> timeline = sut.occupancy(
            List.of(
                closed(MONDAY.atTime(10, 0), MONDAY.atTime(14, 0)),
                closed(MONDAY.atTime(8, 0), MONDAY.atTime(12, 0)),
                closed(MONDAY.atTime(20, 0), MONDAY.atTime(22, 0))
            ),
            from, to);

        assertThat(timeline).containsExactly(
            new OccupancyInterval(from, MONDAY.atTime(8, 0), false),
            new OccupancyInterval(MONDAY.atTime(8, 0), MONDAY.atTime(14, 0), true),
            new OccupancyInterval(MONDAY.atTime(14, 0), MONDAY.atTime(20, 0), false),
            new OccupancyInterval(MONDAY.atTime(20, 0), MONDAY.atTime(22, 0), true),
            new OccupancyInterval(MONDAY.atTime(22, 0), to, false)
        );
    }

    //one leaves at the very moment the other arrives: the house was never empty in between
    @Test
    void should_join_presences_that_only_touch() {
        final LocalDateTime from = MONDAY.atStartOfDay();
        final LocalDateTime to = TUESDAY.atStartOfDay();

        final List<OccupancyInterval> timeline = sut.occupancy(
            List.of(closed(from, MONDAY.atTime(12, 0)), closed(MONDAY.atTime(12, 0), to)), from, to);

        assertThat(timeline).containsExactly(new OccupancyInterval(from, to, true));
    }

    @Test
    void should_report_a_house_nobody_was_in_as_one_empty_stretch() {
        final LocalDateTime from = MONDAY.atStartOfDay();
        final LocalDateTime to = TUESDAY.atStartOfDay();

        assertThat(sut.occupancy(List.of(), from, to)).containsExactly(new OccupancyInterval(from, to, false));
    }

    //the timeline ends at the last check: what lies after it was not observed, so it is not empty
    @Test
    void should_end_the_timeline_at_the_last_check() {
        final LocalDateTime from = MONDAY.atStartOfDay();
        final LocalDateTime lastCheck = MONDAY.atTime(12, 0);

        final List<OccupancyInterval> timeline = sut.occupancy(
            List.of(new PresenceInterval(MONDAY.atTime(6, 0), MONDAY.atTime(13, 0), false)), from, lastCheck);

        assertThat(timeline).containsExactly(
            new OccupancyInterval(from, MONDAY.atTime(6, 0), false),
            new OccupancyInterval(MONDAY.atTime(6, 0), lastCheck, true)
        );
    }

    //a presence seen by a single pass has no length - it does not split an empty stretch in two
    @Test
    void should_leave_a_presence_without_length_out_of_the_timeline() {
        final LocalDateTime from = MONDAY.atStartOfDay();
        final LocalDateTime to = TUESDAY.atStartOfDay();

        final List<OccupancyInterval> timeline = sut.occupancy(
            List.of(closed(MONDAY.atTime(9, 0), MONDAY.atTime(9, 0))), from, to);

        assertThat(timeline).containsExactly(new OccupancyInterval(from, to, false));
    }

    @Test
    void should_answer_no_timeline_when_nothing_was_observed_in_the_range() {
        final LocalDateTime from = MONDAY.atStartOfDay();

        assertThat(sut.occupancy(List.of(closed(from, from.plusHours(1))), from, from)).isEmpty();
    }

    //Monday: occupied until 8, then everyone out until 18. Tuesday: somebody at home all day
    @Test
    void should_tell_the_day_the_house_stood_empty_from_the_day_it_never_did() {
        final LocalDateTime from = MONDAY.atStartOfDay();
        final LocalDateTime to = WEDNESDAY.atStartOfDay();
        final List<OccupancyInterval> timeline = sut.occupancy(
            List.of(closed(from, MONDAY.atTime(8, 0)), closed(MONDAY.atTime(18, 0), to)), from, to);

        final List<DailyOccupancy> days = sut.dailyOccupancy(timeline, from, to);

        assertThat(days).containsExactly(
            new DailyOccupancy(MONDAY, 14 * HOUR, 10 * HOUR, true),
            new DailyOccupancy(TUESDAY, DAY, 0, false)
        );
    }

    @Test
    void should_report_a_day_nobody_came_home_as_empty_throughout() {
        final LocalDateTime from = MONDAY.atStartOfDay();
        final LocalDateTime to = TUESDAY.atStartOfDay();

        final List<DailyOccupancy> days = sut.dailyOccupancy(sut.occupancy(List.of(), from, to), from, to);

        assertThat(days).containsExactly(new DailyOccupancy(MONDAY, 0, DAY, true));
    }

    //the running day is only as long as it was observed: at home since 6, last checked at 12 - the
    //six hours not watched yet are not counted as empty
    @Test
    void should_count_the_running_day_only_up_to_the_last_check() {
        final LocalDateTime from = MONDAY.atStartOfDay();
        final LocalDateTime lastCheck = MONDAY.atTime(12, 0);
        final List<OccupancyInterval> timeline = sut.occupancy(
            List.of(new PresenceInterval(from, lastCheck, true)), from, lastCheck);

        assertThat(sut.dailyOccupancy(timeline, from, lastCheck))
            .containsExactly(new DailyOccupancy(MONDAY, 12 * HOUR, 0, false));
    }

    private static PresenceInterval closed(final LocalDateTime from, final LocalDateTime to) {
        return new PresenceInterval(from, to, false);
    }
}
