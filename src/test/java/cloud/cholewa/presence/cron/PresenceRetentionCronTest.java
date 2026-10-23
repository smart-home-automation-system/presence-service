package cloud.cholewa.presence.cron;

import cloud.cholewa.presence.service.PresenceRetention;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PresenceRetentionCronTest {

    private final PresenceRetention presenceRetention = mock(PresenceRetention.class);
    private final PresenceRetentionCron sut = new PresenceRetentionCron(presenceRetention);

    //HAS-305: should anything outlive the purge's own timeout, the pass ends at its bound
    @Test
    void should_end_a_pass_that_never_completes_at_its_bound() {
        when(presenceRetention.purge()).thenReturn(Mono.never());

        StepVerifier.withVirtualTime(sut::purgeHistory)
            .expectSubscription()
            .expectNoEvent(PresenceRetentionCron.LIMIT.minusSeconds(1))
            .thenAwait(Duration.ofSeconds(1))
            .verifyComplete();
    }
}
