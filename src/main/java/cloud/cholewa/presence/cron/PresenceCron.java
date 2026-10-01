package cloud.cholewa.presence.cron;

import cloud.cholewa.presence.service.PresenceEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

//switched off in the test profile, where a context would otherwise poll a gateway that is not there
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "presence.detection-enabled", havingValue = "true", matchIfMissing = true)
public class PresenceCron {

    private final PresenceEngine presenceEngine;

    @Scheduled(fixedRateString = "PT1M", initialDelayString = "PT15S")
    Mono<Void> detectPresence() {
        return presenceEngine.detect();
    }
}
