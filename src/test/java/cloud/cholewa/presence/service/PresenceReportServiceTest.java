package cloud.cholewa.presence.service;

import cloud.cholewa.presence.client.HouseholdClient;
import cloud.cholewa.presence.database.model.PresenceStatusEntity;
import cloud.cholewa.presence.database.repository.PresenceStatusRepository;
import cloud.cholewa.presence.error.RegistryCallException;
import cloud.cholewa.presence.model.Member;
import cloud.cholewa.presence.model.PresenceInterval;
import cloud.cholewa.presence.model.PresenceReport;
import cloud.cholewa.presence.model.ResidentPresence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static cloud.cholewa.presence.model.PresenceStatus.ABSENT;
import static cloud.cholewa.presence.model.PresenceStatus.PRESENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PresenceReportServiceTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 2, 8, 0);
    private static final LocalDateTime FROM = T0;
    private static final LocalDateTime TO = T0.plusHours(12);

    @Mock
    private HouseholdClient householdClient;

    @Mock
    private PresenceStatusRepository presenceStatusRepository;

    private PresenceReportService sut;

    @BeforeEach
    void setUp() {
        sut = new PresenceReportService(householdClient, presenceStatusRepository, new PresenceIntervalCalculator());
    }

    @Test
    void should_answer_the_latest_row_of_every_active_member_ordered_by_name() {
        when(householdClient.getActiveMembers()).thenReturn(Mono.just(List.of(member("Tom"), member("Anna"))));
        when(presenceStatusRepository.findLatestPerMember()).thenReturn(Flux.just(
            new PresenceStatusEntity(7L, "Tom", ABSENT, T0, T0.plusMinutes(30)),
            new PresenceStatusEntity(5L, "Anna", PRESENT, T0.minusHours(2), T0.plusMinutes(31))
        ));

        sut.getCurrentPresence().as(StepVerifier::create)
            .expectNext(new ResidentPresence("Anna", true, T0.minusHours(2), T0.plusMinutes(31)))
            .expectNext(new ResidentPresence("Tom", false, T0, T0.plusMinutes(30)))
            .verifyComplete();
    }

    @Test
    void should_order_the_names_the_polish_way() {
        when(householdClient.getActiveMembers())
            .thenReturn(Mono.just(List.of(member("Zofia"), member("Łukasz"), member("adam"))));
        when(presenceStatusRepository.findLatestPerMember()).thenReturn(Flux.empty());

        sut.getCurrentPresence().map(ResidentPresence::name).as(StepVerifier::create)
            .expectNext("adam", "Łukasz", "Zofia")
            .verifyComplete();
    }

    //a member who left the registry is no longer watched: their last row would say "present" forever
    @Test
    void should_leave_out_a_member_who_is_no_longer_in_the_active_registry() {
        when(householdClient.getActiveMembers()).thenReturn(Mono.just(List.of(member("Anna"))));
        when(presenceStatusRepository.findLatestPerMember()).thenReturn(Flux.just(
            new PresenceStatusEntity(5L, "Anna", PRESENT, T0, T0),
            new PresenceStatusEntity(6L, "Gone", PRESENT, T0.minusDays(30), T0.minusDays(20))
        ));

        sut.getCurrentPresence().as(StepVerifier::create)
            .expectNext(new ResidentPresence("Anna", true, T0, T0))
            .verifyComplete();
    }

    @Test
    void should_list_a_member_without_a_row_as_not_present() {
        when(householdClient.getActiveMembers()).thenReturn(Mono.just(List.of(member("Anna"))));
        when(presenceStatusRepository.findLatestPerMember()).thenReturn(Flux.empty());

        sut.getCurrentPresence().as(StepVerifier::create)
            .expectNext(new ResidentPresence("Anna", false, null, null))
            .verifyComplete();
    }

    @Test
    void should_answer_an_empty_list_for_an_empty_registry() {
        when(householdClient.getActiveMembers()).thenReturn(Mono.just(List.of()));
        when(presenceStatusRepository.findLatestPerMember()).thenReturn(Flux.empty());

        sut.getCurrentPresence().as(StepVerifier::create).verifyComplete();
    }

    //without the registry nobody can say who is to be listed - guessing from the rows would bring
    //back the members who left
    @Test
    void should_fail_the_current_presence_when_the_registry_cannot_be_read() {
        when(householdClient.getActiveMembers())
            .thenReturn(Mono.error(new RegistryCallException("database-service answered: 500")));
        when(presenceStatusRepository.findLatestPerMember()).thenReturn(Flux.empty());

        sut.getCurrentPresence().as(StepVerifier::create).verifyError(RegistryCallException.class);
    }

    @Test
    void should_report_the_periods_of_a_member_within_the_range() {
        when(presenceStatusRepository.findForReport("Anna", FROM, TO)).thenReturn(Flux.just(
            new PresenceStatusEntity(1L, "Anna", PRESENT, T0.minusHours(1), T0.plusHours(2)),
            new PresenceStatusEntity(3L, "Anna", PRESENT, T0.plusHours(6), T0.plusHours(7))
        ));

        sut.getReport("Anna", FROM, TO).as(StepVerifier::create)
            .expectNext(new PresenceReport("Anna", FROM, TO, List.of(
                new PresenceInterval(FROM, T0.plusHours(2), false),
                new PresenceInterval(T0.plusHours(6), T0.plusHours(7), true)
            )))
            .verifyComplete();

        //a member with a history is answered without the registry, so also while it is down
        verifyNoInteractions(householdClient);
    }

    //the newest row decides what is still going on: here it is an absence, so the presence before
    //it is closed although it is the last period of the report
    @Test
    void should_close_every_period_when_the_newest_row_of_the_member_is_an_absence() {
        when(presenceStatusRepository.findForReport("Anna", FROM, TO)).thenReturn(Flux.just(
            new PresenceStatusEntity(1L, "Anna", PRESENT, T0.plusHours(1), T0.plusHours(2)),
            new PresenceStatusEntity(2L, "Anna", ABSENT, T0.plusHours(2), T0.plusHours(3))
        ));

        sut.getReport("Anna", FROM, TO).as(StepVerifier::create)
            .expectNext(new PresenceReport("Anna", FROM, TO, List.of(
                new PresenceInterval(T0.plusHours(1), T0.plusHours(2), false))))
            .verifyComplete();
    }

    @Test
    void should_answer_an_empty_report_for_an_active_member_without_a_history() {
        when(presenceStatusRepository.findForReport("Anna", FROM, TO)).thenReturn(Flux.empty());
        when(householdClient.getActiveMembers()).thenReturn(Mono.just(List.of(member("Anna"))));

        sut.getReport("Anna", FROM, TO).as(StepVerifier::create)
            .expectNext(new PresenceReport("Anna", FROM, TO, List.of()))
            .verifyComplete();
    }

    @Test
    void should_answer_404_for_a_resident_neither_stored_nor_registered() {
        when(presenceStatusRepository.findForReport("Nobody", FROM, TO)).thenReturn(Flux.empty());
        when(householdClient.getActiveMembers()).thenReturn(Mono.just(List.of(member("Anna"))));

        sut.getReport("Nobody", FROM, TO).as(StepVerifier::create)
            .verifyErrorSatisfies(e -> assertThat(e)
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(exception.getReason()).isEqualTo("Unknown resident: Nobody");
                }));
    }

    //"unknown" cannot be told from "registry down" without the registry, so it is not answered as 404
    @Test
    void should_fail_the_report_of_a_member_without_a_history_when_the_registry_cannot_be_read() {
        when(presenceStatusRepository.findForReport("Anna", FROM, TO)).thenReturn(Flux.empty());
        when(householdClient.getActiveMembers())
            .thenReturn(Mono.error(new RegistryCallException("database-service answered: 500")));

        sut.getReport("Anna", FROM, TO).as(StepVerifier::create).verifyError(RegistryCallException.class);
    }

    @Test
    void should_reject_a_range_that_does_not_run_forward() {
        sut.getReport("Anna", TO, FROM).as(StepVerifier::create).verifyErrorSatisfies(this::isBadRequest);
        sut.getReport("Anna", FROM, FROM).as(StepVerifier::create).verifyErrorSatisfies(this::isBadRequest);

        verifyNoInteractions(presenceStatusRepository, householdClient);
    }

    @Test
    void should_reject_a_range_longer_than_a_year() {
        sut.getReport("Anna", FROM, FROM.plusDays(366).plusSeconds(1)).as(StepVerifier::create)
            .verifyErrorSatisfies(this::isBadRequest);

        verifyNoInteractions(presenceStatusRepository, householdClient);
    }

    //the bounds come straight from the request: date arithmetic on a year at the edge of what
    //LocalDateTime holds would throw, and the caller would get a 500 for a bad range
    @Test
    void should_reject_a_range_at_the_edge_of_the_calendar_instead_of_failing() {
        sut.getReport("Anna", LocalDateTime.MIN, LocalDateTime.MAX).as(StepVerifier::create)
            .verifyErrorSatisfies(this::isBadRequest);

        verifyNoInteractions(presenceStatusRepository, householdClient);
    }

    @Test
    void should_answer_a_valid_range_in_the_last_year_of_the_calendar() {
        final LocalDateTime from = LocalDateTime.MAX.minusDays(1);
        when(presenceStatusRepository.findForReport("Anna", from, LocalDateTime.MAX)).thenReturn(Flux.empty());
        when(householdClient.getActiveMembers()).thenReturn(Mono.just(List.of(member("Anna"))));

        sut.getReport("Anna", from, LocalDateTime.MAX).as(StepVerifier::create)
            .expectNext(new PresenceReport("Anna", from, LocalDateTime.MAX, List.of()))
            .verifyComplete();
    }

    @Test
    void should_accept_a_range_of_a_whole_leap_year() {
        final LocalDateTime to = FROM.plusDays(366);
        when(presenceStatusRepository.findForReport("Anna", FROM, to))
            .thenReturn(Flux.just(new PresenceStatusEntity(2L, "Anna", ABSENT, T0, T0)));

        sut.getReport("Anna", FROM, to).as(StepVerifier::create)
            .expectNext(new PresenceReport("Anna", FROM, to, List.of()))
            .verifyComplete();
    }

    private void isBadRequest(final Throwable throwable) {
        assertThat(throwable).isInstanceOfSatisfying(ResponseStatusException.class, exception ->
            assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    private static Member member(final String name) {
        return new Member(name, Set.of("aa:bb:cc:dd:ee:ff"));
    }
}
