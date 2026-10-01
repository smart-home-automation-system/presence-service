package cloud.cholewa.presence.api;

import cloud.cholewa.presence.config.ExceptionHandlerConfig;
import cloud.cholewa.presence.error.UnifiCallException;
import cloud.cholewa.presence.model.ConnectedClient;
import cloud.cholewa.presence.service.PresenceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;

import java.time.OffsetDateTime;

import static org.mockito.Mockito.when;

//the slice does not scan @Configuration classes, so the handler under test is imported by hand
@WebFluxTest(PresenceController.class)
@Import(ExceptionHandlerConfig.class)
class PresenceControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private PresenceService presenceService;

    @Test
    void should_return_connected_clients() {
        when(presenceService.getConnectedClients()).thenReturn(Flux.just(new ConnectedClient(
            "AA:BB:CC:DD:EE:FF", "Phone", "WIRELESS", OffsetDateTime.parse("2026-09-28T08:15:30Z"))));

        webTestClient.get().uri("/clients")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$[0].macAddress").isEqualTo("aa:bb:cc:dd:ee:ff")
            .jsonPath("$[0].name").isEqualTo("Phone")
            .jsonPath("$[0].type").isEqualTo("WIRELESS");
    }

    //without the processor registered in ExceptionHandlerConfig both cases answer a plain 500
    @Test
    void should_answer_502_when_unifi_call_fails() {
        when(presenceService.getConnectedClients()).thenReturn(Flux.error(
            new UnifiCallException(HttpStatus.BAD_GATEWAY, "UniFi answered: 401")));

        webTestClient.get().uri("/clients")
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY)
            .expectBody()
            .jsonPath("$.errors[0].message").isEqualTo("UniFi call failed")
            .jsonPath("$.errors[0].details").isEqualTo("UniFi answered: 401");
    }

    @Test
    void should_answer_504_when_unifi_does_not_answer_in_time() {
        when(presenceService.getConnectedClients()).thenReturn(Flux.error(
            new UnifiCallException(HttpStatus.GATEWAY_TIMEOUT, "UniFi did not answer within PT10S")));

        webTestClient.get().uri("/clients")
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.GATEWAY_TIMEOUT)
            .expectBody()
            .jsonPath("$.errors[0].details").isEqualTo("UniFi did not answer within PT10S");
    }
}
