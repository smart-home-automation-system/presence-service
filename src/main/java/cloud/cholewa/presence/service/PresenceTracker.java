package cloud.cholewa.presence.service;

import cloud.cholewa.presence.config.PresenceProperties;
import cloud.cholewa.presence.model.Member;
import cloud.cholewa.presence.model.PresenceDecision;
import cloud.cholewa.presence.model.PresenceDecision.Changed;
import cloud.cholewa.presence.model.PresenceDecision.Confirmed;
import cloud.cholewa.presence.model.PresenceStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static cloud.cholewa.presence.model.PresenceStatus.ABSENT;
import static cloud.cholewa.presence.model.PresenceStatus.PRESENT;

//The presence state machine. It holds the current state in memory (the service runs as a single
//instance) and decides, it does not write: evaluate() returns what has to be stored and the status
//of a member moves only when commit() confirms the write - a failed write is simply decided again
//by the next pass.
//
//Inside, time is an Instant: the grace period is a duration, and measured on the local wall clock
//it would shrink to a few minutes or stretch to over an hour on the two nights the clocks change.
//Only the values handed to the store are local date-times, the convention of the org's tables.
@Component
@RequiredArgsConstructor
public class PresenceTracker {

    //passes further apart than this were not consecutive: at least two in a row were skipped
    //(the detection runs once a minute)
    private static final Duration MAX_OBSERVATION_GAP = Duration.ofMinutes(3);

    private final PresenceProperties presenceProperties;
    private final Clock clock;

    private final Map<String, MemberState> states = new ConcurrentHashMap<>();

    //startup: the latest stored row of a member is its current state. A PRESENT row is only ever
    //checked by a pass that saw the member, so its last check is the last sighting.
    public void restore(final String memberName, final PresenceStatus status, final LocalDateTime lastCheckedAt) {
        final MemberState state = new MemberState();
        state.status = status;
        state.lastSeen = status == PRESENT ? lastCheckedAt.atZone(clock.getZone()).toInstant() : null;
        states.put(memberName, state);
    }

    //One pass over the active members. Members that are no longer in the registry (removed or
    //deactivated) are forgotten: nobody watches them, so their last row simply stops being checked,
    //and should they come back, their history continues with a new row - the gap in between is
    //time they were not watched, like any other gap between last_checked_at and the next started_at.
    public List<PresenceDecision> evaluate(
        final Instant now,
        final Collection<Member> members,
        final Set<String> connectedMacAddresses
    ) {
        states.keySet().retainAll(members.stream().map(Member::name).collect(Collectors.toSet()));

        return members.stream()
            .map(member -> evaluate(now, member, connectedMacAddresses))
            .flatMap(Optional::stream)
            .toList();
    }

    public void commit(final PresenceDecision decision) {
        final MemberState state = states.computeIfAbsent(decision.memberName(), name -> new MemberState());
        state.status = decision.status();
        if (decision.status() == ABSENT) {
            state.unseenSince = null;
        }
    }

    private Optional<PresenceDecision> evaluate(final Instant now, final Member member, final Set<String> connected) {
        final MemberState state = states.computeIfAbsent(member.name(), name -> new MemberState());
        final boolean observedWithoutGap = state.lastEvaluated != null
            && !now.isAfter(state.lastEvaluated.plus(MAX_OBSERVATION_GAP));
        state.lastEvaluated = now;

        if (member.macAddresses().stream().anyMatch(connected::contains)) {
            state.lastSeen = now;
            state.unseenSince = null;
            return Optional.of(state.status == PRESENT
                ? new Confirmed(member.name(), PRESENT, local(now))
                : new Changed(member.name(), PRESENT, local(now), local(now)));
        }

        if (state.status == PRESENT) {
            //The grace period is time the member was actually watched and not seen. It starts
            //with the first pass that missed them - not at the last sighting - and starts over
            //when passes were skipped in between: while the gateway (or the registry, or the
            //database) was down nobody was looking, so that time proves nothing. A single pass
            //can miss a client anyway (paging over a live list).
            if (state.unseenSince == null || !observedWithoutGap) {
                state.unseenSince = now;
            }
            if (now.isBefore(state.unseenSince.plus(presenceProperties.absenceThreshold()))) {
                //nothing is written while waiting - the PRESENT row keeps the last sighting as its
                //last check, which is where the absence will start
                return Optional.empty();
            }
            final Instant leftAt = state.lastSeen != null ? state.lastSeen : state.unseenSince;
            return Optional.of(new Changed(member.name(), ABSENT, local(leftAt), local(now)));
        }

        if (state.status == ABSENT) {
            return Optional.of(new Confirmed(member.name(), ABSENT, local(now)));
        }

        //a member watched for the first time and not at home - their history starts as absent
        return Optional.of(new Changed(member.name(), ABSENT, local(now), local(now)));
    }

    private LocalDateTime local(final Instant instant) {
        return LocalDateTime.ofInstant(instant, clock.getZone());
    }

    private static final class MemberState {
        private volatile PresenceStatus status;
        private volatile Instant lastSeen;
        private volatile Instant unseenSince;
        private volatile Instant lastEvaluated;
    }
}
