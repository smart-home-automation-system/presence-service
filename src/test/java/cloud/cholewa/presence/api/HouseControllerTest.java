package cloud.cholewa.presence.api;

import cloud.cholewa.presence.config.ExceptionHandlerConfig;
import cloud.cholewa.presence.model.DailyOccupancy;
import cloud.cholewa.presence.model.HouseReport;
import cloud.cholewa.presence.model.OccupancyInterval;
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
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

//the slice does not scan @Configuration classes, so the handler under test is imported by hand
@WebFluxTest(HouseController.class)
@Import(ExceptionHandlerConfig.class)
class HouseControllerTest {

    private static final LocalDateTime FROM = LocalDateTime.of(2026, 10, 5, 0, 0);
    private static final LocalDateTime TO = LocalDateTime.of(2026, 10, 6, 0, 0);
    private static final String REPORT_URI = "/house/report?from=2026-10-05T00:00:00&to=2026-10-06T00:00:00";

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private PresenceStatisticsService presenceStatisticsService;

    @Test
    void should_return_the_report_of_the_house() {
        when(presenceStatisticsService.getHouseReport(FROM, TO)).thenReturn(Mono.just(new HouseReport(
            FROM, TO, FROM, TO,
            List.of(
                new OccupancyInterval(FROM, FROM.plusHours(9), true),
                new OccupancyInterval(FROM.plusHours(9), TO, false)
            ),
            List.of(new DailyOccupancy(LocalDate.of(2026, 10, 5), 32400, 54000, true)))));

        webTestClient.get().uri(REPORT_URI)
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.from").isEqualTo("2026-10-05T00:00:00")
            .jsonPath("$.to").isEqualTo("2026-10-06T00:00:00")
            .jsonPath("$.observedFrom").isEqualTo("2026-10-05T00:00:00")
            .jsonPath("$.observedUntil").isEqualTo("2026-10-06T00:00:00")
            .jsonPath("$.intervals[0].from").isEqualTo("2026-10-05T00:00:00")
            .jsonPath("$.intervals[0].to").isEqualTo("2026-10-05T09:00:00")
            .jsonPath("$.intervals[0].occupied").isEqualTo(true)
            .jsonPath("$.intervals[1].occupied").isEqualTo(false)
            .jsonPath("$.days[0].date").isEqualTo("2026-10-05")
            .jsonPath("$.days[0].secondsOccupied").isEqualTo(32400)
            .jsonPath("$.days[0].secondsEmpty").isEqualTo(54000)
            .jsonPath("$.days[0].wasEmpty").isEqualTo(true);
    }

    @Test
    void should_answer_400_for_a_range_the_service_rejects() {
        when(presenceStatisticsService.getHouseReport(FROM, TO)).thenReturn(Mono.error(
            new ResponseStatusException(HttpStatus.BAD_REQUEST, "from must be before to")));

        webTestClient.get().uri(REPORT_URI)
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.errors[0].message").isEqualTo("from must be before to");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/house/report?from=2026-10-05T00:00:00",
        "/house/report?from=2026-10-05&to=2026-10-06T00:00:00",
        "/house/report?from=2026-10-05T00:00:00Z&to=2026-10-06T00:00:00"
    })
    void should_answer_400_when_a_bound_of_the_range_is_missing_or_not_a_local_date_time(final String uri) {
        webTestClient.get().uri(URI.create(uri))
            .exchange()
            .expectStatus().isBadRequest();

        verifyNoInteractions(presenceStatisticsService);
    }
}
