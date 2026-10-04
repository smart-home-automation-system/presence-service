package cloud.cholewa.presence.service;

import cloud.cholewa.presence.database.model.PresenceStatusEntity;
import cloud.cholewa.presence.model.PresenceInterval;
import cloud.cholewa.presence.model.PresenceStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

//Turns the stored rows of one member into the periods they were at home within a range. A plain
//class with no I/O, like the tracker, so every edge is unit-tested without a context.
//A PRESENT row is a period from started_at to last_checked_at - for a closed row that is the last
//sighting, where the ABSENT row after it starts. Nothing is added between rows: time the service
//did not watch (the next row starts long after the previous one was last checked) stays a gap.
@Component
public class PresenceIntervalCalculator {

    //The range is closed at its start and open at its end. A period that ended exactly at the start
    //is left out - it would be a presence of no length at the edge, counted again by the range
    //before - while one seen by a single pass inside the range stays.
    //latestRowId is the id of the member's newest row of any status, null when there is none. Only
    //that row can still be going on - and it is reported as open only when its last check lies
    //inside the range, because otherwise the end is the edge of the range and not the last check.
    //Open says the history ends here, not that the member is watched right now: how fresh that is
    //shows in the end of the interval
    public List<PresenceInterval> derive(
        final List<PresenceStatusEntity> rows,
        final Long latestRowId,
        final LocalDateTime from,
        final LocalDateTime to
    ) {
        return rows.stream()
            .filter(row -> row.status() == PresenceStatus.PRESENT)
            .filter(row -> row.startedAt().isBefore(to)
                && (row.lastCheckedAt().isAfter(from) || !row.startedAt().isBefore(from)))
            .sorted(Comparator.comparing(PresenceStatusEntity::id))
            .map(row -> new PresenceInterval(
                later(row.startedAt(), from),
                earlier(row.lastCheckedAt(), to),
                Objects.equals(row.id(), latestRowId) && row.lastCheckedAt().isBefore(to)
            ))
            .toList();
    }

    private static LocalDateTime later(final LocalDateTime first, final LocalDateTime second) {
        return first.isAfter(second) ? first : second;
    }

    private static LocalDateTime earlier(final LocalDateTime first, final LocalDateTime second) {
        return first.isBefore(second) ? first : second;
    }
}
