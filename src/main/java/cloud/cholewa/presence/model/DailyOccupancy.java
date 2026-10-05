package cloud.cholewa.presence.model;

import java.time.LocalDate;

//one day of the whole house; wasEmpty says the house stood empty at some point of the day
public record DailyOccupancy(
    LocalDate date,
    long secondsOccupied,
    long secondsEmpty,
    boolean wasEmpty
) {
}
