package cloud.cholewa.presence.model;

import java.time.LocalDateTime;

//what one detection pass decided for one member - and so what has to be written: a confirmed
//status only moves last_checked_at of the member's latest row, a changed one opens a new row
public sealed interface PresenceDecision {

    String memberName();

    PresenceStatus status();

    record Confirmed(String memberName, PresenceStatus status, LocalDateTime checkedAt) implements PresenceDecision {
    }

    //startedAt can lie before checkedAt: the absence of a member who left starts at the moment they
    //were last seen, while checkedAt is the pass that decided it after the grace period
    record Changed(
        String memberName,
        PresenceStatus status,
        LocalDateTime startedAt,
        LocalDateTime checkedAt
    ) implements PresenceDecision {
    }
}
