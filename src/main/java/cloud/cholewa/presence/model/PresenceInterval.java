package cloud.cholewa.presence.model;

import java.time.LocalDateTime;

//a period a resident was at home, cut to the range that was asked for. open marks the period that
//is still going on: its end is the last check of the resident, not a departure
public record PresenceInterval(
    LocalDateTime from,
    LocalDateTime to,
    boolean open
) {
}
