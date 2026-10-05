package cloud.cholewa.presence.service;

import cloud.cholewa.presence.config.PresenceProperties;
import cloud.cholewa.presence.database.repository.PresenceStatusRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PresenceRetentionTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Warsaw");
    private static final LocalDateTime NOW = LocalDateTime.of(2027, 10, 6, 3, 0);

    @Mock
    private PresenceStatusRepository presenceStatusRepository;

    private PresenceRetention sut;

    @BeforeEach
    void setUp() {
        sut = new PresenceRetention(
            presenceStatusRepository,
            new PresenceProperties(Duration.ofMinutes(10), Duration.ofDays(365)),
            Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE));
    }

    @Test
    void should_delete_what_was_last_checked_before_the_retention_period() {
        final LocalDateTime cutoff = LocalDateTime.of(2026, 10, 6, 3, 0);
        when(presenceStatusRepository.deleteLastCheckedBefore(cutoff)).thenReturn(Mono.just(12L));

        sut.purge().as(StepVerifier::create).verifyComplete();

        verify(presenceStatusRepository).deleteLastCheckedBefore(cutoff);
    }

    //a purge that failed is done by the next one; nothing may fail because of it
    @Test
    void should_not_fail_when_the_delete_does() {
        when(presenceStatusRepository.deleteLastCheckedBefore(NOW.minusDays(365)))
            .thenReturn(Mono.error(new IllegalStateException("database down")));

        sut.purge().as(StepVerifier::create).verifyComplete();
    }

    //the delete holds one of the two pooled connections the detection needs - it must not hold it
    //for good when the database stops answering
    @Test
    void should_give_up_a_delete_that_does_not_answer() {
        when(presenceStatusRepository.deleteLastCheckedBefore(NOW.minusDays(365))).thenReturn(Mono.never());

        StepVerifier.withVirtualTime(() -> sut.purge())
            .thenAwait(Duration.ofSeconds(61))
            .verifyComplete();
    }
}
