package cloud.cholewa.presence.cron;

import cloud.cholewa.commons.scheduling.ScheduledPass;
import cloud.cholewa.presence.service.PresenceEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;

//switched off in the test profile, where a context would otherwise poll a gateway that is not there
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "presence.detection-enabled", havingValue = "true", matchIfMissing = true)
public class PresenceCron {

    //Bounded (HAS-305) a little above the 50 s of the engine's own timeout, which stays inside: should
    //anything outlive it, the pass ends here and the scheduler is not held for good
    static final Duration LIMIT = Duration.ofSeconds(55);

    private final PresenceEngine presenceEngine;

    //fixedDelay, not fixedRate: for a method returning a Mono, Spring waits for the previous run only
    //with a fixed delay - at a fixed rate a slow pass would overlap the next one, and two passes
    //deciding on the same uncommitted state store the same change twice
    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT15S")
    Mono<Void> detectPresence() {
        return ScheduledPass.bounded("presence detection", LIMIT, presenceEngine::detect);
    }
}
