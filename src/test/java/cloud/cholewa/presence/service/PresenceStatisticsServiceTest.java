package cloud.cholewa.presence.service;

import cloud.cholewa.presence.config.PresenceProperties;
import cloud.cholewa.presence.database.model.PresenceStatusEntity;
import cloud.cholewa.presence.database.repository.PresenceStatusRepository;
import cloud.cholewa.presence.model.DailyOccupancy;
import cloud.cholewa.presence.model.DailyPresence;
import cloud.cholewa.presence.model.DailyPresenceReport;
import cloud.cholewa.presence.model.HouseReport;
import cloud.cholewa.presence.model.OccupancyInterval;
import cloud.cholewa.presence.model.PresenceInterval;
import cloud.cholewa.presence.model.PresenceReport;
import cloud.cholewa.presence.service.PresenceReportService.ResidentHistory;
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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static cloud.cholewa.presence.model.PresenceStatus.ABSENT;
import static cloud.cholewa.presence.model.PresenceStatus.PRESENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PresenceStatisticsServiceTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 5);
    private static final LocalDateTime FROM = MONDAY.atStartOfDay();
    private static final LocalDateTime TO = MONDAY.plusDays(1).atStartOfDay();
    private static final LocalDateTime LONG_BEFORE = FROM.minusDays(30);
    private static final long HOUR = 3600;

    @Mock
    private PresenceReportService presenceReportService;

    @Mock
    private PresenceStatusRepository presenceStatusRepository;

    private PresenceTracker presenceTracker;
    private PresenceStatisticsService sut;

    @BeforeEach
    void setUp() {
        final Clock clock = Clock.fixed(Instant.parse("2026-10-07T10:00:00Z"), ZoneId.of("Europe/Warsaw"));
        final PresenceProperties properties = new PresenceProperties(Duration.ofMinutes(10), Duration.ofDays(365));
        presenceTracker = new PresenceTracker(properties, clock);
        sut = new PresenceStatisticsService(
            presenceReportService, presenceStatusRepository,
            new PresenceIntervalCalculator(), new PresenceStatisticsCalculator(clock), presenceTracker,
            new PresenceRetention(presenceStatusRepository, properties, clock));
    }

    @Test
    void should_report_the_days_of_a_resident_from_their_intervals() {
        when(presenceReportService.readHistory("Anna", FROM, TO)).thenReturn(Mono.just(history(
            List.of(new PresenceInterval(MONDAY.atTime(7, 0), MONDAY.atTime(13, 0), false)), TO.plusHours(5))));
        when(presenceStatusRepository.findFirstStartOf("Anna")).thenReturn(Mono.just(LONG_BEFORE));

        sut.getDailyReport("Anna", FROM, TO).as(StepVerifier::create)
            .expectNext(new DailyPresenceReport("Anna", FROM, TO, FROM, TO, List.of(
                new DailyPresence(MONDAY, 6 * HOUR, MONDAY.atTime(7, 0), MONDAY.atTime(13, 0), 25.0))))
            .verifyComplete();
    }

    //the range reaches into time nobody has looked at yet: the day ends at the last check
    @Test
    void should_end_the_days_of_a_resident_at_their_last_check() {
        final LocalDateTime lastCheck = MONDAY.atTime(12, 0);
        when(presenceReportService.readHistory("Anna", FROM, TO)).thenReturn(Mono.just(history(
            List.of(new PresenceInterval(MONDAY.atTime(6, 0), lastCheck, true)), lastCheck)));
        when(presenceStatusRepository.findFirstStartOf("Anna")).thenReturn(Mono.just(LONG_BEFORE));

        sut.getDailyReport("Anna", FROM, TO).as(StepVerifier::create)
            .expectNext(new DailyPresenceReport("Anna", FROM, TO, FROM, lastCheck, List.of(
                new DailyPresence(MONDAY, 6 * HOUR, MONDAY.atTime(6, 0), null, 50.0))))
            .verifyComplete();
    }

    //the resident was added to the registry on Monday at 6: the hours before were not watched, so
    //they are no time away - 9 of the 18 observed hours at home, not of 24
    @Test
    void should_start_the_days_of_a_resident_where_their_history_starts() {
        final LocalDateTime firstStart = MONDAY.atTime(6, 0);
        when(presenceReportService.readHistory("Anna", FROM, TO)).thenReturn(Mono.just(history(
            List.of(new PresenceInterval(firstStart, MONDAY.atTime(15, 0), false)), TO.plusHours(5))));
        when(presenceStatusRepository.findFirstStartOf("Anna")).thenReturn(Mono.just(firstStart));

        sut.getDailyReport("Anna", FROM, TO).as(StepVerifier::create)
            .expectNext(new DailyPresenceReport("Anna", FROM, TO, firstStart, TO, List.of(
                new DailyPresence(MONDAY, 9 * HOUR, firstStart, MONDAY.atTime(15, 0), 50.0))))
            .verifyComplete();
    }

    @Test
    void should_answer_no_day_for_a_resident_nothing_is_stored_for() {
        when(presenceReportService.readHistory("Anna", FROM, TO)).thenReturn(Mono.just(history(List.of(), null)));

        sut.getDailyReport("Anna", FROM, TO).as(StepVerifier::create)
            .expectNext(new DailyPresenceReport("Anna", FROM, TO, null, null, List.of()))
            .verifyComplete();
    }

    //a resident last checked before the range starts - one who left the registry, or a range in
    //the future - or first stored after it ends: nothing was observed in it
    @Test
    void should_answer_no_day_for_a_range_outside_the_history_of_the_resident() {
        final DailyPresenceReport nothing = new DailyPresenceReport("Anna", FROM, TO, null, null, List.of());

        when(presenceReportService.readHistory("Anna", FROM, TO))
            .thenReturn(Mono.just(history(List.of(), FROM.minusDays(3))));
        when(presenceStatusRepository.findFirstStartOf("Anna")).thenReturn(Mono.just(LONG_BEFORE));
        sut.getDailyReport("Anna", FROM, TO).as(StepVerifier::create).expectNext(nothing).verifyComplete();

        when(presenceReportService.readHistory("Anna", FROM, TO))
            .thenReturn(Mono.just(history(List.of(), TO.plusDays(3))));
        when(presenceStatusRepository.findFirstStartOf("Anna")).thenReturn(Mono.just(TO.plusDays(1)));
        sut.getDailyReport("Anna", FROM, TO).as(StepVerifier::create).expectNext(nothing).verifyComplete();
    }

    //who is unknown and which range is valid is decided by the interval report, once
    @Test
    void should_pass_on_the_404_of_an_unknown_resident() {
        when(presenceReportService.readHistory("Nobody", FROM, TO)).thenReturn(Mono.error(
            new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown resident: Nobody")));

        sut.getDailyReport("Nobody", FROM, TO).as(StepVerifier::create)
            .verifyErrorSatisfies(e -> assertThat(e).isInstanceOfSatisfying(ResponseStatusException.class,
                exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND)));
    }

    //Anna until 8 and from 18, Tom 7-9: occupied until 9, empty until 18, occupied to the end
    @Test
    void should_report_the_house_from_the_presence_of_everyone() {
        final PresenceStatusEntity annaNow = new PresenceStatusEntity(4L, "Anna", PRESENT, MONDAY.atTime(18, 0), TO.plusHours(5));
        observed(LONG_BEFORE, TO.plusHours(5), annaNow);
        when(presenceStatusRepository.findPresentBetween(FROM, TO)).thenReturn(Flux.just(
            new PresenceStatusEntity(1L, "Anna", PRESENT, FROM.minusHours(4), MONDAY.atTime(8, 0)),
            new PresenceStatusEntity(2L, "Tom", PRESENT, MONDAY.atTime(7, 0), MONDAY.atTime(9, 0)),
            annaNow
        ));

        sut.getHouseReport(FROM, TO).as(StepVerifier::create)
            .expectNext(new HouseReport(FROM, TO, FROM, TO,
                List.of(
                    new OccupancyInterval(FROM, MONDAY.atTime(9, 0), true),
                    new OccupancyInterval(MONDAY.atTime(9, 0), MONDAY.atTime(18, 0), false),
                    new OccupancyInterval(MONDAY.atTime(18, 0), TO, true)
                ),
                List.of(new DailyOccupancy(MONDAY, 15 * HOUR, 9 * HOUR, true))))
            .verifyComplete();
    }

    //the time since the last pass is not time the house stood empty: at home since before midnight,
    //last checked at 12, asked for the whole day - occupied all the observed time, never empty
    @Test
    void should_end_the_house_report_at_the_last_check_instead_of_counting_the_rest_as_empty() {
        final LocalDateTime lastCheck = MONDAY.atTime(12, 0);
        final PresenceStatusEntity anna = new PresenceStatusEntity(1L, "Anna", PRESENT, FROM.minusHours(4), lastCheck);
        observed(LONG_BEFORE, lastCheck, anna);
        when(presenceStatusRepository.findPresentBetween(FROM, TO)).thenReturn(Flux.just(anna));

        sut.getHouseReport(FROM, TO).as(StepVerifier::create)
            .expectNext(new HouseReport(FROM, TO, FROM, lastCheck,
                List.of(new OccupancyInterval(FROM, lastCheck, true)),
                List.of(new DailyOccupancy(MONDAY, 12 * HOUR, 0, false))))
            .verifyComplete();
    }

    //Anna's phone dropped off the Wi-Fi half an hour ago and the grace period started over after a
    //restart: her row is not checked while it runs, while Tom's absence is confirmed every minute
    //and moves the last check on. She is still "present" for the service - the tracker says so -
    //so that half hour is not an empty house, however old her row is
    @Test
    void should_keep_the_house_occupied_while_the_absence_of_the_only_one_at_home_is_undecided() {
        final LocalDateTime lastCheck = MONDAY.atTime(12, 0);
        final PresenceStatusEntity anna =
            new PresenceStatusEntity(1L, "Anna", PRESENT, FROM.minusHours(4), lastCheck.minusMinutes(30));
        presenceTracker.restore("Anna", PRESENT, anna.lastCheckedAt());
        presenceTracker.restore("Tom", ABSENT, lastCheck);
        final PresenceStatusEntity tom = new PresenceStatusEntity(2L, "Tom", ABSENT, FROM.minusHours(9), lastCheck);
        observed(LONG_BEFORE, lastCheck, anna, tom);
        when(presenceStatusRepository.findPresentBetween(FROM, TO)).thenReturn(Flux.just(anna));

        sut.getHouseReport(FROM, TO).as(StepVerifier::create)
            .expectNext(new HouseReport(FROM, TO, FROM, lastCheck,
                List.of(new OccupancyInterval(FROM, lastCheck, true)),
                List.of(new DailyOccupancy(MONDAY, 12 * HOUR, 0, false))))
            .verifyComplete();
    }

    //the last row of a member who left the registry while present is never closed; carried on, it
    //would keep the house occupied for good. The tracker has forgotten them, so it is not
    @Test
    void should_not_carry_on_the_presence_of_a_member_the_tracker_no_longer_watches() {
        final LocalDateTime lastCheck = MONDAY.atTime(12, 0);
        presenceTracker.restore("Tom", ABSENT, lastCheck);
        final PresenceStatusEntity gone =
            new PresenceStatusEntity(1L, "Gone", PRESENT, FROM.minusHours(4), MONDAY.atTime(8, 0));
        final PresenceStatusEntity tom = new PresenceStatusEntity(2L, "Tom", ABSENT, FROM.minusHours(9), lastCheck);
        observed(LONG_BEFORE, lastCheck, gone, tom);
        when(presenceStatusRepository.findPresentBetween(FROM, TO)).thenReturn(Flux.just(gone));

        sut.getHouseReport(FROM, TO).as(StepVerifier::create)
            .expectNext(new HouseReport(FROM, TO, FROM, lastCheck,
                List.of(
                    new OccupancyInterval(FROM, MONDAY.atTime(8, 0), true),
                    new OccupancyInterval(MONDAY.atTime(8, 0), lastCheck, false)
                ),
                List.of(new DailyOccupancy(MONDAY, 8 * HOUR, 4 * HOUR, true))))
            .verifyComplete();
    }

    @Test
    void should_report_an_observed_day_nobody_was_at_home_as_empty() {
        observed(LONG_BEFORE, TO.plusHours(5));
        when(presenceStatusRepository.findPresentBetween(FROM, TO)).thenReturn(Flux.empty());

        sut.getHouseReport(FROM, TO).as(StepVerifier::create)
            .expectNext(new HouseReport(FROM, TO, FROM, TO,
                List.of(new OccupancyInterval(FROM, TO, false)),
                List.of(new DailyOccupancy(MONDAY, 0, 24 * HOUR, true))))
            .verifyComplete();
    }

    //the history starts at 6 on Monday: the hours before it were not watched, so they are not empty
    @Test
    void should_start_the_house_report_where_the_history_starts() {
        final LocalDateTime firstStart = MONDAY.atTime(6, 0);
        final PresenceStatusEntity anna = new PresenceStatusEntity(1L, "Anna", PRESENT, firstStart, TO.plusHours(5));
        observed(firstStart, TO.plusHours(5), anna);
        when(presenceStatusRepository.findPresentBetween(FROM, TO)).thenReturn(Flux.just(anna));

        sut.getHouseReport(FROM, TO).as(StepVerifier::create)
            .expectNext(new HouseReport(FROM, TO, firstStart, TO,
                List.of(new OccupancyInterval(firstStart, TO, true)),
                List.of(new DailyOccupancy(MONDAY, 18 * HOUR, 0, false))))
            .verifyComplete();
    }

    //Tom has been away for two years: one row, kept because it is checked every minute, starting
    //long before the retention horizon. What Anna did back then was deleted since - so the report
    //must not count from Tom's old start and call the purged days an empty house
    @Test
    void should_not_report_anything_before_the_retention_horizon_as_observed() {
        final LocalDateTime now = LocalDateTime.of(2026, 10, 7, 12, 0);
        final LocalDateTime horizon = now.minusDays(365);
        final LocalDateTime from = horizon.minusDays(1);
        final LocalDateTime to = horizon.plusHours(12);
        final PresenceStatusEntity tom = new PresenceStatusEntity(1L, "Tom", ABSENT, horizon.minusDays(365), now);
        observed(horizon.minusDays(365), now, tom);
        when(presenceStatusRepository.findPresentBetween(from, to)).thenReturn(Flux.empty());

        sut.getHouseReport(from, to).as(StepVerifier::create)
            .assertNext(report -> {
                assertThat(report.observedFrom()).isEqualTo(horizon);
                assertThat(report.intervals()).containsExactly(new OccupancyInterval(horizon, to, false));
                assertThat(report.days()).hasSize(1);
            })
            .verifyComplete();

        when(presenceReportService.readHistory("Tom", from, to)).thenReturn(Mono.just(
            new ResidentHistory(new PresenceReport("Tom", from, to, List.of()), now)));
        when(presenceStatusRepository.findFirstStartOf("Tom")).thenReturn(Mono.just(horizon.minusDays(365)));

        sut.getDailyReport("Tom", from, to).as(StepVerifier::create)
            .assertNext(report -> {
                assertThat(report.observedFrom()).isEqualTo(horizon);
                assertThat(report.days()).hasSize(1);
            })
            .verifyComplete();
    }

    //an empty table, or a range outside everything ever observed: not an empty house - nothing known
    @Test
    void should_answer_nothing_for_the_house_when_nothing_was_observed_in_the_range() {
        final HouseReport nothing = new HouseReport(FROM, TO, null, null, List.of(), List.of());

        when(presenceStatusRepository.findFirstStart()).thenReturn(Mono.empty());
        sut.getHouseReport(FROM, TO).as(StepVerifier::create).expectNext(nothing).verifyComplete();

        when(presenceStatusRepository.findFirstStart()).thenReturn(Mono.just(LONG_BEFORE));
        when(presenceStatusRepository.findLastCheck()).thenReturn(Mono.just(FROM.minusDays(1)));
        sut.getHouseReport(FROM, TO).as(StepVerifier::create).expectNext(nothing).verifyComplete();

        when(presenceStatusRepository.findFirstStart()).thenReturn(Mono.just(TO.plusDays(1)));
        when(presenceStatusRepository.findLastCheck()).thenReturn(Mono.just(TO.plusDays(2)));
        sut.getHouseReport(FROM, TO).as(StepVerifier::create).expectNext(nothing).verifyComplete();

        verify(presenceStatusRepository, never()).findPresentBetween(any(), any());
    }

    @Test
    void should_reject_an_invalid_range_for_the_house_report() {
        sut.getHouseReport(TO, FROM).as(StepVerifier::create).verifyErrorSatisfies(this::isBadRequest);
        sut.getHouseReport(FROM, FROM.plusDays(367)).as(StepVerifier::create).verifyErrorSatisfies(this::isBadRequest);

        verifyNoInteractions(presenceStatusRepository);
    }

    private void observed(
        final LocalDateTime firstStart,
        final LocalDateTime lastCheck,
        final PresenceStatusEntity... latestRows
    ) {
        when(presenceStatusRepository.findFirstStart()).thenReturn(Mono.just(firstStart));
        when(presenceStatusRepository.findLastCheck()).thenReturn(Mono.just(lastCheck));
        when(presenceStatusRepository.findLatestPerMember()).thenReturn(Flux.just(latestRows));
    }

    private void isBadRequest(final Throwable throwable) {
        assertThat(throwable).isInstanceOfSatisfying(ResponseStatusException.class, exception ->
            assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    private static ResidentHistory history(final List<PresenceInterval> intervals, final LocalDateTime lastCheckedAt) {
        return new ResidentHistory(new PresenceReport("Anna", FROM, TO, intervals), lastCheckedAt);
    }
}
