package cloud.cholewa.presence.service;

import cloud.cholewa.presence.client.HouseholdClient;
import cloud.cholewa.presence.client.UnifiClient;
import cloud.cholewa.presence.error.PresenceStateException;
import cloud.cholewa.presence.error.RegistryCallException;
import cloud.cholewa.presence.error.UnifiCallException;
import cloud.cholewa.presence.model.ConnectedClient;
import cloud.cholewa.presence.model.Member;
import cloud.cholewa.presence.model.PresenceDecision;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

//One detection pass: the clients connected to the network, matched against the devices of the
//active household members, decided by the tracker and written by the store.
@Slf4j
@Service
@RequiredArgsConstructor
public class PresenceEngine {

    private static final Duration PASS_TIMEOUT = Duration.ofSeconds(50);
    private static final Duration RESTORE_TIMEOUT = Duration.ofSeconds(20);

    private final UnifiClient unifiClient;
    private final HouseholdClient householdClient;
    private final PresenceTracker presenceTracker;
    private final PresenceStatusStore presenceStatusStore;
    private final DetectionAlerts detectionAlerts;
    private final Clock clock;

    //the registry as it was last read - used when database-service does not answer, so that an
    //outage there is not read as a household without members
    private final AtomicReference<List<Member>> lastKnownMembers = new AtomicReference<>();

    //the state is rebuilt from the latest row of every member once, before the first pass; a failed
    //read is not cached, so the next pass tries again instead of starting from an empty state.
    //The read carries its own timeout, inside the cache: a cached Mono is not cancelled when its
    //subscriber is, so without it a query that never answers would outlive the pass timeout and
    //every later pass would wait on the same dead query.
    private final Mono<Boolean> restored = Mono.defer(() -> restoreState()
            .timeout(RESTORE_TIMEOUT)
            .onErrorMap(PresenceEngine::restoreFailure))
        .cache(done -> Duration.ofMillis(Long.MAX_VALUE), error -> Duration.ZERO, () -> Duration.ZERO);

    //A pass never fails: when the gateway, the registry (with nothing read before) or the database
    //cannot be reached it is skipped and the state stays as it was - one missed poll must not turn
    //everyone absent. Each pass is told to DetectionAlerts as evaluated or skipped (HAS-476), so that
    //passes that never run are not silent; the telling never fails nor waits.
    public Mono<Void> detect() {
        return restored
            .then(Mono.zip(connectedMacAddresses(), activeMembers()))
            .flatMapMany(observed -> Flux.fromIterable(presenceTracker.evaluate(
                clock.instant(), observed.getT2(), observed.getT1())))
            .concatMap(this::store)
            .collectList()
            .flatMap(PresenceEngine::requireAWrite)
            //the passes run one after another (fixedDelay), so a call that never answers - a query
            //on a broken pooled connection, as in the 2026-09-26 outage of database-service - would
            //stop the detection for good; bounded, it costs one pass
            .timeout(PASS_TIMEOUT)
            .then(Mono.<Void>fromRunnable(detectionAlerts::evaluated))
            .onErrorResume(e -> {
                final String cause = causeOf(e);
                log.warn("Presence pass skipped, the state is kept: {}", cause);
                detectionAlerts.skipped(cause);
                return Mono.empty();
            });
    }

    //the cause in fixed words: the messages of the clients are built here, never a body of the gateway;
    //anything else is named by its type alone
    static String causeOf(final Throwable error) {
        if (error instanceof UnifiCallException || error instanceof RegistryCallException
            || error instanceof PresenceStateException) {
            return error.getMessage();
        }
        if (error instanceof TimeoutException) {
            return "the pass did not finish within " + PASS_TIMEOUT;
        }
        return "the pass failed: " + error.getClass().getSimpleName();
    }

    //a pass none of whose decisions could be written decided nothing a reader can see: the database is
    //down, and the pass counts as skipped (HAS-476) - one failed member among others stays a pass
    private static Mono<Void> requireAWrite(final List<Optional<Throwable>> writes) {
        return Mono.just(writes)
            .filter(all -> all.isEmpty() || all.stream().anyMatch(Optional::isEmpty))
            .switchIfEmpty(Mono.error(() -> new PresenceStateException(
                "the presence state could not be written to the database: "
                    + NestedExceptionUtils.getMostSpecificCause(writes.getLast().orElseThrow()).getClass().getSimpleName(),
                writes.getLast().orElseThrow())))
            .then();
    }

    private static Throwable restoreFailure(final Throwable error) {
        if (error instanceof TimeoutException) {
            return new PresenceStateException(
                "the presence state was not read from the database within " + RESTORE_TIMEOUT, error);
        }
        return new PresenceStateException("the presence state could not be read from the database: "
            + NestedExceptionUtils.getMostSpecificCause(error).getClass().getSimpleName(), error);
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
    //next pass. Answers the failure of the write, empty when it was written
    private Mono<Optional<Throwable>> store(final PresenceDecision decision) {
        return presenceStatusStore.apply(decision)
            .doOnSuccess(written -> presenceTracker.commit(decision))
            .then(Mono.just(Optional.<Throwable>empty()))
            .onErrorResume(e -> {
                log.error("Presence of {} not stored: {}", decision.memberName(), e.getMessage());
                return Mono.just(Optional.of(e));
            });
    }
}
