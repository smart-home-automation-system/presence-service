package cloud.cholewa.presence.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Optional;

//The rules every report shares for the range it is asked for: it runs forward and spans at most
//the retention horizon of the history, a year - nothing older is kept, so nothing longer is answered.
final class ReportRange {

    private static final long MAX_DAYS = 366;

    private ReportRange() {
    }

    //what is wrong with the range, as the 400 to answer - empty for a valid one
    static Optional<ResponseStatusException> violation(final LocalDateTime from, final LocalDateTime to) {
        if (!from.isBefore(to)) {
            return Optional.of(new ResponseStatusException(HttpStatus.BAD_REQUEST, "from must be before to"));
        }
        if (isLongerThanLimit(from, to)) {
            return Optional.of(new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "The range must not be longer than " + MAX_DAYS + " days"));
        }
        return Optional.empty();
    }

    //Counted in calendar days on the local dates, with the time of day deciding a range of exactly
    //the limit. Not from.plusYears(1) or plusDays: the bounds come straight from the request, and
    //adding to a date at the edge of what LocalDateTime holds throws instead of answering 400
    private static boolean isLongerThanLimit(final LocalDateTime from, final LocalDateTime to) {
        final long days = to.toLocalDate().toEpochDay() - from.toLocalDate().toEpochDay();

        return days > MAX_DAYS || (days == MAX_DAYS && to.toLocalTime().isAfter(from.toLocalTime()));
    }
}
