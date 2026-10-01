package cloud.cholewa.presence.service;

import cloud.cholewa.presence.config.PresenceProperties;
import cloud.cholewa.presence.model.Member;
import cloud.cholewa.presence.model.PresenceDecision;
import cloud.cholewa.presence.model.PresenceDecision.Changed;
import cloud.cholewa.presence.model.PresenceDecision.Confirmed;
import cloud.cholewa.presence.model.PresenceStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static cloud.cholewa.presence.model.PresenceStatus.ABSENT;
import static cloud.cholewa.presence.model.PresenceStatus.PRESENT;

//The presence state machine. It holds the current state in memory (the service runs as a single
//replica) and decides, it does not write: evaluate() returns what has to be stored and the status
//of a member moves only when commit() confirms the write - a failed write is simply decided again
//by the next pass.
@Component
@RequiredArgsConstructor
public class PresenceTracker {

    private final PresenceProperties presenceProperties;

    private final Map<String, MemberState> states = new ConcurrentHashMap<>();

    //startup: the latest stored row of a member is its current state. For a PRESENT member the last
    //check of that row stands in for the moment they were last seen; the grace period itself starts
    //with the first pass that does not see them, so a restart alone never turns anyone absent.
    public void restore(final String memberName, final PresenceStatus status, final LocalDateTime lastCheckedAt) {
        final MemberState state = new MemberState();
        state.status = status;
        state.lastSeen = status == PRESENT ? lastCheckedAt : null;
        states.put(memberName, state);
    }

    public List<PresenceDecision> evaluate(
        final LocalDateTime now,
        final Collection<Member> members,
        final Set<String> connectedMacAddresses
    ) {
        return members.stream()
            .map(member -> evaluate(now, member, connectedMacAddresses))
            .toList();
    }

    public void commit(final PresenceDecision decision) {
        final MemberState state = states.computeIfAbsent(decision.memberName(), name -> new MemberState());
        state.status = decision.status();
        if (decision.status() == ABSENT) {
            state.unseenSince = null;
        }
    }

    private PresenceDecision evaluate(final LocalDateTime now, final Member member, final Set<String> connected) {
        final MemberState state = states.computeIfAbsent(member.name(), name -> new MemberState());

        if (member.macAddresses().stream().anyMatch(connected::contains)) {
            state.lastSeen = now;
            state.unseenSince = null;
            return state.status == PRESENT
                ? new Confirmed(member.name(), PRESENT, now)
                : new Changed(member.name(), PRESENT, now, null, now);
        }

        if (state.status == PRESENT) {
            //the grace period is counted from the first pass that missed the member, not from the
            //last sighting: passes skipped during an outage of the gateway are not observations,
            //and a single pass can miss a client anyway (paging over a live list)
            if (state.unseenSince == null) {
                state.unseenSince = now;
            }
            if (now.isBefore(state.unseenSince.plus(presenceProperties.absenceThreshold()))) {
                return new Confirmed(member.name(), PRESENT, now);
            }
            //the absence starts when the member was last seen, not when the grace period ran out
            final LocalDateTime leftAt = state.lastSeen != null ? state.lastSeen : state.unseenSince;
            return new Changed(member.name(), ABSENT, leftAt, leftAt, now);
        }

        if (state.status == ABSENT) {
            return new Confirmed(member.name(), ABSENT, now);
        }

        //a member seen for the first time and not at home - their history starts as absent
        return new Changed(member.name(), ABSENT, now, null, now);
    }

    private static final class MemberState {
        private PresenceStatus status;
        private LocalDateTime lastSeen;
        private LocalDateTime unseenSince;
    }
}
