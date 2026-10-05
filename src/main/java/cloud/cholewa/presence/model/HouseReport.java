package cloud.cholewa.presence.model;

import java.time.LocalDateTime;
import java.util.List;

//intervals is one timeline from observedFrom to observedUntil, occupied and empty stretches in
//turn. The two bound the part of the range the history covers: from the first row ever stored (or
//the start of the range, when that comes later) to the last check of anyone (or the end of the
//range, when that comes first). Both are null when nothing was observed inside the range
public record HouseReport(
    LocalDateTime from,
    LocalDateTime to,
    LocalDateTime observedFrom,
    LocalDateTime observedUntil,
    List<OccupancyInterval> intervals,
    List<DailyOccupancy> days
) {

    public HouseReport {
        intervals = List.copyOf(intervals);
        days = List.copyOf(days);
    }
}
