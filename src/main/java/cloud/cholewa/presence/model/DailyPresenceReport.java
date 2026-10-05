package cloud.cholewa.presence.model;

import java.time.LocalDateTime;
import java.util.List;

//observedUntil is where the statistics end: the last check of the resident, or the end of the
//range when that comes first. Null when nothing was observed inside the range
public record DailyPresenceReport(
    String name,
    LocalDateTime from,
    LocalDateTime to,
    LocalDateTime observedUntil,
    List<DailyPresence> days
) {

    public DailyPresenceReport {
        days = List.copyOf(days);
    }
}
