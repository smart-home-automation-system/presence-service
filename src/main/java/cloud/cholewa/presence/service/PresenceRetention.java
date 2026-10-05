package cloud.cholewa.presence.service;

import cloud.cholewa.presence.config.PresenceProperties;
import cloud.cholewa.presence.database.repository.PresenceStatusRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;

//Deletes the presence history older than presence.retention. A row goes by its last check, not by
//its start: the row a member is in right now is checked every minute however long ago it started -
//a year at home, or a year away - so it survives, while the last row of a member who left the
//registry stops being checked and goes a retention period later.
@Slf4j
@Service
@RequiredArgsConstructor
public class PresenceRetention {

    private static final Duration PURGE_TIMEOUT = Duration.ofSeconds(60);

    private final PresenceStatusRepository presenceStatusRepository;
    private final PresenceProperties presenceProperties;
    private final Clock clock;

    //Where the kept history starts right now: nothing last checked before it survives a purge. The
    //one definition of it - the job deletes by it, and the statistics do not count anything before
    //it as observed.
    public LocalDateTime horizon() {
        return LocalDateTime.now(clock).minus(presenceProperties.retention());
    }

    //Never fails: a purge that did not run is simply done by the next one, a day later, and nothing
    //else may depend on it - least of all the detection. Bounded, so that a database that stopped
    //answering does not keep the job waiting for good. The timeout only stops waiting: it does
    //not abort the statement on the server, which may still complete - and the connection goes
    //back to the pool, whose validation on acquire is what discards it if it is broken.
    //Everything happens inside defer: Spring calls a reactive @Scheduled method once, at startup,
    //and subscribes to the Mono it got again for every run - a cutoff computed outside would stay
    //the one of the day the service started, and the job would delete nothing new until a restart.
    public Mono<Void> purge() {
        return Mono.defer(() -> {
                final LocalDateTime cutoff = horizon();

                return presenceStatusRepository.deleteLastCheckedBefore(cutoff)
                    .timeout(PURGE_TIMEOUT)
                    .doOnNext(deleted -> log.info(
                        "Presence history retention: {} row(s) last checked before {} deleted", deleted, cutoff));
            })
            .then()
            .onErrorResume(e -> {
                //the exception itself, not only its message: this line is all there is to go on
                //for a job that runs once a night and never fails
                log.error("Presence history retention did not complete, the next run makes up for it", e);
                return Mono.empty();
            });
    }
}
