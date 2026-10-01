package cloud.cholewa.presence.service;

import cloud.cholewa.presence.client.HouseholdClient;
import cloud.cholewa.presence.client.UnifiClient;
import cloud.cholewa.presence.model.ConnectedClient;
import cloud.cholewa.presence.model.Member;
import cloud.cholewa.presence.model.PresenceDecision;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

//One detection pass: the clients connected to the network, matched against the devices of the
//active household members, decided by the tracker and written by the store.
@Slf4j
@Service
@RequiredArgsConstructor
public class PresenceEngine {

    private final UnifiClient unifiClient;
    private final HouseholdClient householdClient;
    private final PresenceTracker presenceTracker;
    private final PresenceStatusStore presenceStatusStore;
    private final Clock clock;

    //the registry as it was last read - used when database-service does not answer, so that an
    //outage there is not read as a household without members
    private final AtomicReference<List<Member>> lastKnownMembers = new AtomicReference<>();

    //the state is rebuilt from the latest row of every member once, before the first pass; a failed
    //read is not cached, so the next pass tries again instead of starting from an empty state
    private final Mono<Boolean> restored = Mono.defer(this::restoreState)
        .cache(done -> Duration.ofMillis(Long.MAX_VALUE), error -> Duration.ZERO, () -> Duration.ZERO);

    //A pass never fails: when the gateway, the registry (with nothing read before) or the database
    //cannot be reached it is skipped and the state stays as it was - one missed poll must not turn
    //everyone absent.
    public Mono<Void> detect() {
        return restored
            .then(Mono.zip(connectedMacAddresses(), activeMembers()))
            .flatMapMany(observed -> Flux.fromIterable(presenceTracker.evaluate(
                LocalDateTime.now(clock), observed.getT2(), observed.getT1())))
            .concatMap(this::store)
            .then()
            .onErrorResume(e -> {
                log.warn("Presence pass skipped, the state is kept: {}", e.getMessage());
                return Mono.empty();
            });
    }

    private Mono<Boolean> restoreState() {
        return presenceStatusStore.findLatestPerMember()
            .doOnNext(row -> presenceTracker.restore(row.memberName(), row.status(), row.lastCheckedAt()))
            .count()
            .doOnNext(count -> log.info("Presence state restored for {} member(s)", count))
            .thenReturn(true);
    }

    private Mono<Set<String>> connectedMacAddresses() {
        return unifiClient.getConnectedClients()
            .map(ConnectedClient::macAddress)
            .collect(Collectors.toSet());
    }

    private Mono<List<Member>> activeMembers() {
        return householdClient.getActiveMembers()
            .doOnNext(lastKnownMembers::set)
            .onErrorResume(e -> {
                final List<Member> lastKnown = lastKnownMembers.get();
                if (lastKnown == null) {
                    return Mono.error(e);
                }
                log.warn("Household registry not read, using the last known one: {}", e.getMessage());
                return Mono.just(lastKnown);
            });
    }

    //each member is written on its own: a failed write must not cancel the members after it, and
    //because the tracker moves only on a successful write, the same decision is made again by the
    //next pass
    private Mono<Void> store(final PresenceDecision decision) {
        return presenceStatusStore.apply(decision)
            .doOnSuccess(written -> presenceTracker.commit(decision))
            .onErrorResume(e -> {
                log.error("Presence of {} not stored: {}", decision.memberName(), e.getMessage());
                return Mono.empty();
            });
    }
}
