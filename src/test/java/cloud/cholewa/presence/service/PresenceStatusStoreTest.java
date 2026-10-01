package cloud.cholewa.presence.service;

import cloud.cholewa.presence.database.model.PresenceStatusEntity;
import cloud.cholewa.presence.database.repository.PresenceStatusRepository;
import cloud.cholewa.presence.model.PresenceDecision.Changed;
import cloud.cholewa.presence.model.PresenceDecision.Confirmed;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;

import static cloud.cholewa.presence.model.PresenceStatus.ABSENT;
import static cloud.cholewa.presence.model.PresenceStatus.PRESENT;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PresenceStatusStoreTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 2, 8, 0);

    @Mock
    private PresenceStatusRepository presenceStatusRepository;

    @InjectMocks
    private PresenceStatusStore sut;

    //the explicit requirement of the table: no row per poll
    @Test
    void should_only_move_last_checked_at_when_the_status_is_confirmed() {
        when(presenceStatusRepository.touchLatest("Anna", T0)).thenReturn(Mono.just(1));

        sut.apply(new Confirmed("Anna", PRESENT, T0)).as(StepVerifier::create).verifyComplete();

        verify(presenceStatusRepository, never()).save(any());
    }

    @Test
    void should_insert_a_row_when_the_status_changes() {
        final PresenceStatusEntity row = new PresenceStatusEntity(null, "Anna", PRESENT, T0, T0);
        when(presenceStatusRepository.save(row)).thenReturn(Mono.just(row));

        sut.apply(new Changed("Anna", PRESENT, T0, null, T0)).as(StepVerifier::create).verifyComplete();

        verify(presenceStatusRepository).save(row);
        verify(presenceStatusRepository, never()).touchLatest(any(), any());
    }

    //a member who left: the PRESENT row ends at the last sighting, the ABSENT row starts there and
    //carries the time of the pass that decided it
    @Test
    void should_end_the_previous_row_before_inserting_the_new_one() {
        final LocalDateTime decidedAt = T0.plusMinutes(11);
        final PresenceStatusEntity row = new PresenceStatusEntity(null, "Anna", ABSENT, T0, decidedAt);
        when(presenceStatusRepository.touchLatest("Anna", T0)).thenReturn(Mono.just(1));
        when(presenceStatusRepository.save(row)).thenReturn(Mono.just(row));

        sut.apply(new Changed("Anna", ABSENT, T0, T0, decidedAt)).as(StepVerifier::create).verifyComplete();

        final InOrder inOrder = inOrder(presenceStatusRepository);
        inOrder.verify(presenceStatusRepository).touchLatest("Anna", T0);
        inOrder.verify(presenceStatusRepository).save(row);
    }

    @Test
    void should_open_the_period_again_when_there_is_no_row_to_confirm() {
        final PresenceStatusEntity row = new PresenceStatusEntity(null, "Anna", PRESENT, T0, T0);
        when(presenceStatusRepository.touchLatest("Anna", T0)).thenReturn(Mono.just(0));
        when(presenceStatusRepository.save(row)).thenReturn(Mono.just(row));

        sut.apply(new Confirmed("Anna", PRESENT, T0)).as(StepVerifier::create).verifyComplete();

        verify(presenceStatusRepository).save(row);
    }

    @Test
    void should_fail_when_the_write_fails() {
        when(presenceStatusRepository.touchLatest("Anna", T0))
            .thenReturn(Mono.error(new IllegalStateException("database down")));

        sut.apply(new Confirmed("Anna", PRESENT, T0)).as(StepVerifier::create)
            .verifyError(IllegalStateException.class);
    }
}
