package cloud.cholewa.presence.model;

import java.time.LocalDateTime;

//what one detection pass decided for one member - and so what has to be written: a confirmed
//status only moves last_checked_at of the member's latest row, a changed one opens a new row
public sealed interface PresenceDecision {

    String memberName();

    PresenceStatus status();

    record Confirmed(String memberName, PresenceStatus status, LocalDateTime checkedAt) implements PresenceDecision {
    }

    //previousEndedAt is set when the row being left has to end earlier than its last check - the
    //PRESENT row of a member who left ends at the moment they were last seen, not at the end of
    //the grace period; null leaves the previous row as it is
    record Changed(
        String memberName,
        PresenceStatus status,
        LocalDateTime startedAt,
        LocalDateTime previousEndedAt,
        LocalDateTime checkedAt
    ) implements PresenceDecision {
    }
}
