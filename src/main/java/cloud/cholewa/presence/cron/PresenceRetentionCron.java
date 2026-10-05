package cloud.cholewa.presence.cron;

import cloud.cholewa.presence.service.PresenceRetention;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

//A class of its own, apart from PresenceCron: the two share nothing, and the detection must go on
//whatever happens here.
@Component
@RequiredArgsConstructor
public class PresenceRetentionCron {

    private final PresenceRetention presenceRetention;

    //once a day at a quiet hour, in the zone the service runs in. 03:00 exists exactly once also on
    //the two nights the clocks change (02:00-03:00 is the hour that is skipped or repeated).
    //The schedule has its only default in application.yaml; "-" switches the job off, which the
    //test profile does.
    @Scheduled(cron = "${presence.retention-cron}")
    Mono<Void> purgeHistory() {
        return presenceRetention.purge();
    }
}
