package cloud.cholewa.presence.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

//one day of one resident. firstArrival and lastDeparture are real ones: a presence carried over
//midnight is no arrival, one still going on - or cut off by the range - no departure, so both can
//be null on a day spent at home. The percentage is of the part of the day that was observed
public record DailyPresence(
    LocalDate date,
    long secondsAtHome,
    LocalDateTime firstArrival,
    LocalDateTime lastDeparture,
    double presencePercentage
) {
}
