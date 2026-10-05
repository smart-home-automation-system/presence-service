package cloud.cholewa.presence.service;

import cloud.cholewa.presence.model.DailyOccupancy;
import cloud.cholewa.presence.model.DailyPresence;
import cloud.cholewa.presence.model.OccupancyInterval;
import cloud.cholewa.presence.model.PresenceInterval;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

//Statistics on top of the intervals PresenceIntervalCalculator derives: days of one resident, and
//the occupancy of the whole house. A plain class with no I/O and no clock of its own - what it
//counts ends at "until", which the caller takes from the data (the last check), not from the time
//of the request: the minute since the last pass is not time the house stood empty.
//Days are calendar days in the zone the history is stored in, and their length is measured in that
//zone, so the two days a year the clocks change are 23 and 25 hours long.
@Component
public class PresenceStatisticsCalculator {

    private final ZoneId zone;

    public PresenceStatisticsCalculator(final Clock clock) {
        this.zone = clock.getZone();
    }

    //One entry per calendar day between observedFrom and until, also for a day spent away. The
    //intervals are those of one resident, already cut to the range from..to.
    public List<DailyPresence> dailyPresence(
        final List<PresenceInterval> intervals,
        final LocalDateTime from,
        final LocalDateTime to,
        final LocalDateTime observedFrom,
        final LocalDateTime until
    ) {
        final List<PresenceInterval> usable = intervals.stream().filter(PresenceStatisticsCalculator::runsForward).toList();
        final List<Span> atHome = merge(usable.stream().map(interval -> new Span(interval.from(), interval.to())).toList());

        return days(observedFrom, until).stream()
            .map(day -> {
                final long secondsAtHome = secondsWithin(atHome, day);
                //a day ends before its last moment, so that midnight belongs to one day only - but
                //the statistics end at "until", and an arrival stored by the very last pass falls
                //exactly on it
                final Predicate<LocalDateTime> inDay =
                    moment -> day.contains(moment) || (moment.equals(until) && day.end().equals(until));

                return new DailyPresence(
                    day.date(),
                    secondsAtHome,
                    //an interval starting exactly where the range does was cut off there, or might
                    //have been - it does not count as an arrival
                    usable.stream()
                        .map(PresenceInterval::from)
                        .filter(start -> !start.equals(from) && inDay.test(start))
                        .min(Comparator.naturalOrder())
                        .orElse(null),
                    //the same at the other end, and a presence still going on has not ended at all
                    usable.stream()
                        .filter(interval -> !interval.open())
                        .map(PresenceInterval::to)
                        .filter(end -> !end.equals(to) && inDay.test(end))
                        .max(Comparator.naturalOrder())
                        .orElse(null),
                    percentage(secondsAtHome, seconds(day.start(), day.end()))
                );
            })
            .toList();
    }

    //The house from "from" to "until" as one timeline: occupied while anyone is at home - the union
    //of the intervals of all residents - and empty in between. Nothing is interpolated, so time the
    //service did not watch reads as empty, like in the report of a single resident.
    public List<OccupancyInterval> occupancy(
        final List<PresenceInterval> intervals,
        final LocalDateTime from,
        final LocalDateTime until
    ) {
        if (!from.isBefore(until)) {
            return List.of();
        }

        final List<Span> occupied = merge(intervals.stream()
            .filter(PresenceStatisticsCalculator::runsForward)
            .map(interval -> new Span(later(interval.from(), from), earlier(interval.to(), until)))
            //a presence seen by a single pass has no length, and neither has one cut down to nothing
            .filter(span -> span.from().isBefore(span.to()))
            .toList());

        final List<OccupancyInterval> timeline = new ArrayList<>();
        LocalDateTime cursor = from;

        for (final Span span : occupied) {
            if (cursor.isBefore(span.from())) {
                timeline.add(new OccupancyInterval(cursor, span.from(), false));
            }
            timeline.add(new OccupancyInterval(span.from(), span.to(), true));
            cursor = span.to();
        }
        if (cursor.isBefore(until)) {
            timeline.add(new OccupancyInterval(cursor, until, false));
        }

        return timeline;
    }

    //The days of the timeline occupancy() built: how long the house was occupied and empty. Both
    //are summed from the timeline, and wasEmpty is read from it, not from the seconds - the stored
    //times are finer than a second, so an empty stretch shorter than one would otherwise be in
    //the timeline and missing from the flag.
    public List<DailyOccupancy> dailyOccupancy(
        final List<OccupancyInterval> timeline,
        final LocalDateTime from,
        final LocalDateTime until
    ) {
        final List<Span> occupied = spans(timeline, true);
        final List<Span> empty = spans(timeline, false);

        return days(from, until).stream()
            .map(day -> new DailyOccupancy(
                day.date(),
                secondsWithin(occupied, day),
                secondsWithin(empty, day),
                empty.stream().anyMatch(span -> span.from().isBefore(day.end()) && span.to().isAfter(day.start()))
            ))
            .toList();
    }

    private static List<Span> spans(final List<OccupancyInterval> timeline, final boolean occupied) {
        return timeline.stream()
            .filter(interval -> interval.occupied() == occupied)
            .map(interval -> new Span(interval.from(), interval.to()))
            .toList();
    }

    //the calendar days from..until touches, the first and the last one cut to it
    private static List<Day> days(final LocalDateTime from, final LocalDateTime until) {
        final List<Day> days = new ArrayList<>();
        LocalDateTime start = from;

        while (start.isBefore(until)) {
            final LocalDateTime nextMidnight = start.toLocalDate().plusDays(1).atStartOfDay();
            final LocalDateTime end = earlier(nextMidnight, until);
            days.add(new Day(start.toLocalDate(), start, end));
            start = end;
        }

        return days;
    }

    //overlapping and touching spans become one. Spans of one resident never overlap - except on the
    //night the clocks go back, when the stored local times repeat an hour
    private static List<Span> merge(final List<Span> spans) {
        final List<Span> merged = new ArrayList<>();

        spans.stream()
            .sorted(Comparator.comparing(Span::from))
            .forEach(span -> {
                if (!merged.isEmpty() && !span.from().isAfter(merged.getLast().to())) {
                    final Span last = merged.removeLast();
                    merged.add(new Span(last.from(), later(last.to(), span.to())));
                } else {
                    merged.add(span);
                }
            });

        return merged;
    }

    private long secondsWithin(final List<Span> spans, final Day day) {
        return spans.stream()
            .mapToLong(span -> seconds(later(span.from(), day.start()), earlier(span.to(), day.end())))
            .sum();
    }

    //measured in the zone, so an interval across a clock change has its real length; never negative
    private long seconds(final LocalDateTime from, final LocalDateTime to) {
        return from.isBefore(to) ? Duration.between(from.atZone(zone), to.atZone(zone)).getSeconds() : 0;
    }

    private static double percentage(final long part, final long whole) {
        return whole <= 0 ? 0 : Math.round(Math.min(part, whole) * 1000.0 / whole) / 10.0;
    }

    //On the night the clocks go back a row can be stored with its last check before its start; such
    //an interval has no usable length and is left out instead of being counted backwards
    private static boolean runsForward(final PresenceInterval interval) {
        return !interval.to().isBefore(interval.from());
    }

    private static LocalDateTime later(final LocalDateTime first, final LocalDateTime second) {
        return first.isAfter(second) ? first : second;
    }

    private static LocalDateTime earlier(final LocalDateTime first, final LocalDateTime second) {
        return first.isBefore(second) ? first : second;
    }

    private record Span(LocalDateTime from, LocalDateTime to) {
    }

    private record Day(LocalDate date, LocalDateTime start, LocalDateTime end) {

        boolean contains(final LocalDateTime moment) {
            return !moment.isBefore(start) && moment.isBefore(end);
        }
    }
}
