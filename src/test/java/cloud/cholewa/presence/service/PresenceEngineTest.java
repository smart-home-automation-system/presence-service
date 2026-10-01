package cloud.cholewa.presence.service;

import cloud.cholewa.presence.client.HouseholdClient;
import cloud.cholewa.presence.client.UnifiClient;
import cloud.cholewa.presence.config.PresenceProperties;
import cloud.cholewa.presence.database.model.PresenceStatusEntity;
import cloud.cholewa.presence.error.RegistryCallException;
import cloud.cholewa.presence.error.UnifiCallException;
import cloud.cholewa.presence.model.ConnectedClient;
import cloud.cholewa.presence.model.Member;
import cloud.cholewa.presence.model.PresenceDecision;
import cloud.cholewa.presence.model.PresenceDecision.Changed;
import cloud.cholewa.presence.model.PresenceDecision.Confirmed;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import static cloud.cholewa.presence.model.PresenceStatus.ABSENT;
import static cloud.cholewa.presence.model.PresenceStatus.PRESENT;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PresenceEngineTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Warsaw");
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 2, 8, 0);
    private static final String PHONE = "02:00:00:00:00:01";
    private static final Member ANNA = new Member("Anna", Set.of(PHONE));
    private static final Member JAN = new Member("Jan", Set.of("02:00:00:00:00:02"));

    @Mock
    private UnifiClient unifiClient;
    @Mock
    private HouseholdClient householdClient;
    @Mock
    private PresenceStatusStore presenceStatusStore;

    private final MutableClock clock = new MutableClock();
    private PresenceEngine sut;

    @BeforeEach
    void setUp() {
        sut = new PresenceEngine(
            unifiClient,
            householdClient,
            new PresenceTracker(new PresenceProperties(Duration.ofMinutes(10))),
            presenceStatusStore,
            clock
        );
        lenient().when(presenceStatusStore.findLatestPerMember()).thenReturn(Flux.empty());
        lenient().when(presenceStatusStore.apply(any())).thenReturn(Mono.empty());
        lenient().when(householdClient.getActiveMembers()).thenReturn(Mono.just(List.of(ANNA)));
    }

    @Test
    void should_store_arrival_and_then_only_confirm() {
        connected(PHONE);

        detectAt(T0);
        detectAt(T0.plusMinutes(1));

        verify(presenceStatusStore).apply(new Changed("Anna", PRESENT, T0, null, T0));
        verify(presenceStatusStore).apply(new Confirmed("Anna", PRESENT, T0.plusMinutes(1)));
    }

    @Test
    void should_store_absence_after_the_grace_period() {
        connected(PHONE);
        detectAt(T0);
        connected();
        detectAt(T0.plusMinutes(1));
        detectAt(T0.plusMinutes(11));

        verify(presenceStatusStore).apply(new Changed("Anna", ABSENT, T0, T0, T0.plusMinutes(11)));
    }

    //one failed poll of the gateway must not turn everyone absent
    @Test
    void should_skip_the_pass_and_keep_the_state_when_the_gateway_fails() {
        connected(PHONE);
        detectAt(T0);

        when(unifiClient.getConnectedClients())
            .thenReturn(Flux.error(new UnifiCallException(HttpStatus.GATEWAY_TIMEOUT, "UniFi did not answer")));
        detectAt(T0.plusMinutes(1));

        connected(PHONE);
        detectAt(T0.plusMinutes(2));

        //arrival, nothing for the failed pass, then a plain confirmation
        verify(presenceStatusStore, times(2)).apply(any());
        verify(presenceStatusStore).apply(new Confirmed("Anna", PRESENT, T0.plusMinutes(2)));
    }

    //an outage of database-service is not a household without members
    @Test
    void should_use_the_last_known_registry_when_the_registry_fails() {
        connected(PHONE);
        detectAt(T0);

        when(householdClient.getActiveMembers())
            .thenReturn(Mono.error(new RegistryCallException("database-service call failed")));
        detectAt(T0.plusMinutes(1));

        verify(presenceStatusStore).apply(new Confirmed("Anna", PRESENT, T0.plusMinutes(1)));
    }

    @Test
    void should_skip_the_pass_when_the_registry_fails_and_was_never_read() {
        connected(PHONE);
        when(householdClient.getActiveMembers())
            .thenReturn(Mono.error(new RegistryCallException("database-service call failed")));

        detectAt(T0);

        verify(presenceStatusStore, never()).apply(any());
    }

    @Test
    void should_do_nothing_for_an_empty_registry() {
        connected(PHONE);
        when(householdClient.getActiveMembers()).thenReturn(Mono.just(List.of()));

        detectAt(T0);

        verify(presenceStatusStore, never()).apply(any());
    }

    //startup recovery: the latest stored row is the current state, so the first pass confirms it
    @Test
    void should_restore_the_state_from_the_latest_rows_before_the_first_pass() {
        when(presenceStatusStore.findLatestPerMember()).thenReturn(Flux.just(
            new PresenceStatusEntity(7L, "Anna", PRESENT, T0.minusHours(2), T0.minusMinutes(1))));
        connected(PHONE);

        detectAt(T0);
        detectAt(T0.plusMinutes(1));

        verify(presenceStatusStore).apply(new Confirmed("Anna", PRESENT, T0));
        verify(presenceStatusStore, times(1)).findLatestPerMember();
    }

    @Test
    void should_skip_the_pass_and_restore_again_when_the_state_cannot_be_read() {
        when(presenceStatusStore.findLatestPerMember())
            .thenReturn(Flux.error(new IllegalStateException("database down")))
            .thenReturn(Flux.just(new PresenceStatusEntity(7L, "Anna", PRESENT, T0.minusHours(2), T0.minusMinutes(1))));
        connected(PHONE);

        detectAt(T0);
        verify(presenceStatusStore, never()).apply(any());

        detectAt(T0.plusMinutes(1));
        verify(presenceStatusStore).apply(new Confirmed("Anna", PRESENT, T0.plusMinutes(1)));
    }

    //a failed write of one member neither cancels the members after it nor moves the state:
    //the same decision is made again by the next pass
    @Test
    void should_store_the_other_members_and_repeat_the_decision_when_a_write_fails() {
        when(householdClient.getActiveMembers()).thenReturn(Mono.just(List.of(ANNA, JAN)));
        connected(PHONE);
        final PresenceDecision annaArrives = new Changed("Anna", PRESENT, T0, null, T0);
        when(presenceStatusStore.apply(annaArrives)).thenReturn(Mono.error(new IllegalStateException("database down")));

        detectAt(T0);
        verify(presenceStatusStore).apply(new Changed("Jan", ABSENT, T0, null, T0));

        detectAt(T0.plusMinutes(1));
        verify(presenceStatusStore).apply(new Changed("Anna", PRESENT, T0.plusMinutes(1), null, T0.plusMinutes(1)));
        verify(presenceStatusStore).apply(new Confirmed("Jan", ABSENT, T0.plusMinutes(1)));
    }

    private void connected(final String... macAddresses) {
        lenient().when(unifiClient.getConnectedClients()).thenReturn(Flux.fromArray(macAddresses)
            .map(mac -> new ConnectedClient(mac, "device", "WIRELESS", null)));
    }

    private void detectAt(final LocalDateTime now) {
        clock.now = now;
        sut.detect().as(StepVerifier::create).verifyComplete();
    }

    //a hand-written clock instead of a Mockito mock: Mockito 5.23 cannot mock the sealed ZoneId
    private static final class MutableClock extends Clock {
        private LocalDateTime now = T0;

        @Override
        public ZoneId getZone() {
            return ZONE;
        }

        @Override
        public Clock withZone(final ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.atZone(ZONE).toInstant();
        }
    }
}
