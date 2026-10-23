package cloud.cholewa.presence.cron;

import cloud.cholewa.presence.service.PresenceEngine;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PresenceCronTest {

    private final PresenceEngine presenceEngine = mock(PresenceEngine.class);
    private final PresenceCron sut = new PresenceCron(presenceEngine);

    //HAS-305: should anything outlive the engine's own timeout, the pass ends at its bound and the
    //scheduler is not held for good
    @Test
    void should_end_a_pass_that_never_completes_at_its_bound() {
        when(presenceEngine.detect()).thenReturn(Mono.never());

        StepVerifier.withVirtualTime(sut::detectPresence)
            .expectSubscription()
            .expectNoEvent(PresenceCron.LIMIT.minusSeconds(1))
            .thenAwait(Duration.ofSeconds(1))
            .verifyComplete();
    }

    //Spring calls the method once and subscribes to the same Mono for every run
    @Test
    void should_detect_anew_for_every_run() {
        when(presenceEngine.detect()).thenReturn(Mono.empty());

        final Mono<Void> scheduled = sut.detectPresence();
        scheduled.as(StepVerifier::create).verifyComplete();
        scheduled.as(StepVerifier::create).verifyComplete();

        verify(presenceEngine, times(2)).detect();
    }
}
