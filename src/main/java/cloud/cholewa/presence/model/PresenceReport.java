package cloud.cholewa.presence.model;

import java.time.LocalDateTime;
import java.util.List;

public record PresenceReport(
    String name,
    LocalDateTime from,
    LocalDateTime to,
    List<PresenceInterval> intervals
) {

    public PresenceReport {
        intervals = List.copyOf(intervals);
    }
}
