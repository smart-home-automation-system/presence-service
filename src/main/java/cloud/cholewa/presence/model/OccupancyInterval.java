package cloud.cholewa.presence.model;

import java.time.LocalDateTime;

//a stretch of time the house was occupied (at least one resident at home) or empty
public record OccupancyInterval(
    LocalDateTime from,
    LocalDateTime to,
    boolean occupied
) {
}
