package cloud.cholewa.presence.model;

import java.time.LocalDateTime;
import java.util.List;

//observedFrom and observedUntil bound the part of the range the history of the resident covers:
//from their first stored status (or the start of the range, when that comes later) to their last
//check (or the end of the range, when that comes first). Both null when nothing was observed
//inside the range
public record DailyPresenceReport(
    String name,
    LocalDateTime from,
    LocalDateTime to,
    LocalDateTime observedFrom,
    LocalDateTime observedUntil,
    List<DailyPresence> days
) {

    public DailyPresenceReport {
        days = List.copyOf(days);
    }
}
