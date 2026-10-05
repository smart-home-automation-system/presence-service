package cloud.cholewa.presence.api;

import cloud.cholewa.presence.config.ExceptionHandlerConfig;
import cloud.cholewa.presence.error.RegistryCallException;
import cloud.cholewa.presence.model.DailyPresence;
import cloud.cholewa.presence.model.DailyPresenceReport;
import cloud.cholewa.presence.model.PresenceInterval;
import cloud.cholewa.presence.model.PresenceReport;
import cloud.cholewa.presence.model.ResidentPresence;
import cloud.cholewa.presence.service.PresenceReportService;
import cloud.cholewa.presence.service.PresenceStatisticsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

//the slice does not scan @Configuration classes, so the handler under test is imported by hand
@WebFluxTest(ResidentController.class)
@Import(ExceptionHandlerConfig.class)
class ResidentControllerTest {

    private static final LocalDateTime FROM = LocalDateTime.of(2026, 10, 1, 0, 0);
    private static final LocalDateTime TO = LocalDateTime.of(2026, 10, 2, 0, 0);
    private static final String REPORT_URI = "/residents/Anna/report?from=2026-10-01T00:00:00&to=2026-10-02T00:00:00";

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private PresenceReportService presenceReportService;

    @MockitoBean
    private PresenceStatisticsService presenceStatisticsService;

    @Test
    void should_return_the_current_presence_of_every_resident() {
        when(presenceReportService.getCurrentPresence()).thenReturn(Flux.just(
            new ResidentPresence("Anna", true, FROM.plusHours(7), FROM.plusHours(9).plusMinutes(30)),
            new ResidentPresence("Tom", false, null, null)
        ));

        webTestClient.get().uri("/residents/presence")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$[0].name").isEqualTo("Anna")
            .jsonPath("$[0].present").isEqualTo(true)
            .jsonPath("$[0].since").isEqualTo("2026-10-01T07:00:00")
            .jsonPath("$[0].lastCheckedAt").isEqualTo("2026-10-01T09:30:00")
            .jsonPath("$[1].name").isEqualTo("Tom")
            .jsonPath("$[1].present").isEqualTo(false)
            .jsonPath("$[1].since").isEmpty();
    }

    @Test
    void should_return_the_report_of_a_resident() {
        when(presenceReportService.getReport("Anna", FROM, TO)).thenReturn(Mono.just(new PresenceReport(
            "Anna", FROM, TO, List.of(new PresenceInterval(FROM.plusHours(7), FROM.plusHours(9), true)))));

        webTestClient.get().uri(REPORT_URI)
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.name").isEqualTo("Anna")
            .jsonPath("$.from").isEqualTo("2026-10-01T00:00:00")
            .jsonPath("$.to").isEqualTo("2026-10-02T00:00:00")
            .jsonPath("$.intervals[0].from").isEqualTo("2026-10-01T07:00:00")
            .jsonPath("$.intervals[0].to").isEqualTo("2026-10-01T09:00:00")
            .jsonPath("$.intervals[0].open").isEqualTo(true);
    }

    //a name is free text in the registry, so it arrives percent-encoded
    @Test
    void should_decode_the_name_of_the_resident() {
        when(presenceReportService.getReport("Anna Maria", FROM, TO))
            .thenReturn(Mono.just(new PresenceReport("Anna Maria", FROM, TO, List.of())));

        webTestClient.get().uri("/residents/{name}/report?from={from}&to={to}", "Anna Maria", FROM, TO)
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.name").isEqualTo("Anna Maria")
            .jsonPath("$.intervals").isEmpty();
    }

    @Test
    void should_answer_404_for_an_unknown_resident() {
        when(presenceReportService.getReport("Anna", FROM, TO)).thenReturn(Mono.error(
            new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown resident: Anna")));

        webTestClient.get().uri(REPORT_URI)
            .exchange()
            .expectStatus().isNotFound()
            .expectBody()
            .jsonPath("$.errors[0].message").isEqualTo("Unknown resident: Anna");
    }

    @Test
    void should_answer_400_for_a_range_the_service_rejects() {
        when(presenceReportService.getReport("Anna", FROM, TO)).thenReturn(Mono.error(
            new ResponseStatusException(HttpStatus.BAD_REQUEST, "from must be before to")));

        webTestClient.get().uri(REPORT_URI)
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.errors[0].message").isEqualTo("from must be before to");
    }

    @Test
    void should_answer_400_when_a_bound_of_the_range_is_missing() {
        webTestClient.get().uri("/residents/Anna/report?from=2026-10-01T00:00:00")
            .exchange()
            .expectStatus().isBadRequest();

        verifyNoInteractions(presenceReportService);
    }

    //a date alone is not a range bound, and an offset must not be dropped silently - the caller
    //would get a report shifted by the offset without knowing
    @ParameterizedTest
    @ValueSource(strings = {"2026-10-01", "2026-10-01T00:00:00Z", "2026-10-01T00:00:00%2B02:00", "yesterday"})
    void should_answer_400_when_a_bound_of_the_range_is_not_a_local_date_time(final String from) {
        webTestClient.get()
            .uri(URI.create("/residents/Anna/report?from=" + from + "&to=2026-10-02T00:00:00"))
            .exchange()
            .expectStatus().isBadRequest();

        verifyNoInteractions(presenceReportService);
    }

    @Test
    void should_return_the_daily_report_of_a_resident() {
        when(presenceStatisticsService.getDailyReport("Anna", FROM, TO)).thenReturn(Mono.just(new DailyPresenceReport(
            "Anna", FROM, TO, FROM.plusHours(12),
            List.of(new DailyPresence(LocalDate.of(2026, 10, 1), 21600, FROM.plusHours(6), null, 50.0)))));

        webTestClient.get().uri("/residents/Anna/report/daily?from=2026-10-01T00:00:00&to=2026-10-02T00:00:00")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.name").isEqualTo("Anna")
            .jsonPath("$.observedUntil").isEqualTo("2026-10-01T12:00:00")
            .jsonPath("$.days[0].date").isEqualTo("2026-10-01")
            .jsonPath("$.days[0].secondsAtHome").isEqualTo(21600)
            .jsonPath("$.days[0].firstArrival").isEqualTo("2026-10-01T06:00:00")
            .jsonPath("$.days[0].lastDeparture").isEmpty()
            .jsonPath("$.days[0].presencePercentage").isEqualTo(50.0);
    }

    @Test
    void should_answer_404_for_the_daily_report_of_an_unknown_resident() {
        when(presenceStatisticsService.getDailyReport("Anna", FROM, TO)).thenReturn(Mono.error(
            new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown resident: Anna")));

        webTestClient.get().uri("/residents/Anna/report/daily?from=2026-10-01T00:00:00&to=2026-10-02T00:00:00")
            .exchange()
            .expectStatus().isNotFound()
            .expectBody()
            .jsonPath("$.errors[0].message").isEqualTo("Unknown resident: Anna");
    }

    @Test
    void should_answer_400_when_a_bound_of_the_daily_report_is_not_a_local_date_time() {
        webTestClient.get()
            .uri(URI.create("/residents/Anna/report/daily?from=2026-10-01T00:00:00Z&to=2026-10-02T00:00:00"))
            .exchange()
            .expectStatus().isBadRequest();

        verifyNoInteractions(presenceStatisticsService);
    }

    //without the processor registered in ExceptionHandlerConfig this answers a plain 500
    @Test
    void should_answer_502_when_the_registry_cannot_be_read() {
        when(presenceReportService.getCurrentPresence()).thenReturn(Flux.error(
            new RegistryCallException("database-service answered: 500")));

        webTestClient.get().uri("/residents/presence")
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY)
            .expectBody()
            //nothing of what is behind this service leaves it: no service name, no failure class
            .jsonPath("$.errors[0].message").isEqualTo("Household registry unavailable")
            .jsonPath("$.errors[0].details").doesNotExist();
    }
}
