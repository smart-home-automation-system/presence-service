package cloud.cholewa.presence.client;

import cloud.cholewa.presence.config.UnifiProperties;
import cloud.cholewa.presence.error.UnifiCallException;
import cloud.cholewa.presence.model.ConnectedClient;
import lombok.SneakyThrows;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class UnifiClientTest {

    private static final String SITE_ID = "88f7af54-98f8-306a-a1c7-c9349722b1f6";
    private static final Duration RESPONSE_TIMEOUT = Duration.ofMillis(300);

    private static final String SITES = """
        {
            "offset": 0, "limit": 200, "count": 1, "totalCount": 1,
            "data": [{"id": "%s", "internalReference": "default", "name": "Default"}]
        }
        """.formatted(SITE_ID);

    private static final String EMPTY_PAGE = """
        {"offset": 0, "limit": 200, "count": 0, "totalCount": 0, "data": []}
        """;

    private MockWebServer mockWebServer;
    private UnifiClient sut;

    @BeforeEach
    @SneakyThrows
    void setUp() {
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        UnifiProperties properties = new UnifiProperties(
            "localhost", "secret-key", "Default", "00", Duration.ofSeconds(1), RESPONSE_TIMEOUT);

        WebClient webClient = WebClient.builder()
            .baseUrl("http://localhost:" + mockWebServer.getPort())
            .clientConnector(new ReactorClientHttpConnector(HttpClient.create().responseTimeout(RESPONSE_TIMEOUT)))
            .build();

        sut = new UnifiClient(webClient, properties);
    }

    @AfterEach
    void tearDown() {
        mockWebServer.close();
    }

    @Test
    @SneakyThrows
    void should_return_connected_clients_with_lowercase_mac() {
        enqueueJson(SITES);
        enqueueJson("""
            {
                "offset": 0, "limit": 200, "count": 1, "totalCount": 1,
                "data": [{
                    "type": "WIRELESS", "id": "0a1b2c3d-0000-0000-0000-000000000001", "name": "Phone",
                    "connectedAt": "2026-09-28T08:15:30Z", "ipAddress": "192.168.1.10",
                    "macAddress": "AA:BB:CC:DD:EE:FF", "access": {"type": "DEFAULT"}
                }]
            }
            """);

        sut.getConnectedClients()
            .as(StepVerifier::create)
            .assertNext(client -> {
                assertThat(client.macAddress()).isEqualTo("aa:bb:cc:dd:ee:ff");
                assertThat(client.name()).isEqualTo("Phone");
                assertThat(client.type()).isEqualTo("WIRELESS");
                assertThat(client.connectedAt()).isNotNull();
            })
            .verifyComplete();

        RecordedRequest sites = mockWebServer.takeRequest();
        assertThat(sites.getUrl().encodedPath()).isEqualTo("/proxy/network/integration/v1/sites");
        assertThat(sites.getHeaders().get("X-API-Key")).isEqualTo("secret-key");

        RecordedRequest clients = mockWebServer.takeRequest();
        assertThat(clients.getUrl().encodedPath())
            .isEqualTo("/proxy/network/integration/v1/sites/" + SITE_ID + "/clients");
        assertThat(clients.getUrl().queryParameter("offset")).isEqualTo("0");
        assertThat(clients.getUrl().queryParameter("limit")).isEqualTo("200");
        assertThat(clients.getHeaders().get("X-API-Key")).isEqualTo("secret-key");
    }

    @Test
    void should_skip_client_without_mac_address() {
        enqueueJson(SITES);
        enqueueJson("""
            {
                "offset": 0, "limit": 200, "count": 2, "totalCount": 2,
                "data": [
                    {"type": "VPN", "name": "Laptop on VPN"},
                    {"type": "WIRED", "name": "TV", "macAddress": "11:22:33:44:55:66"}
                ]
            }
            """);

        sut.getConnectedClients()
            .map(ConnectedClient::macAddress)
            .as(StepVerifier::create)
            .expectNext("11:22:33:44:55:66")
            .verifyComplete();
    }

    @Test
    @SneakyThrows
    void should_fetch_every_page() {
        enqueueJson(SITES);
        enqueueJson("""
            {
                "offset": 0, "limit": 200, "count": 2, "totalCount": 3,
                "data": [{"macAddress": "00:00:00:00:00:01"}, {"macAddress": "00:00:00:00:00:02"}]
            }
            """);
        enqueueJson("""
            {
                "offset": 2, "limit": 200, "count": 1, "totalCount": 3,
                "data": [{"macAddress": "00:00:00:00:00:03"}]
            }
            """);

        sut.getConnectedClients()
            .map(ConnectedClient::macAddress)
            .as(StepVerifier::create)
            .expectNext("00:00:00:00:00:01", "00:00:00:00:00:02", "00:00:00:00:00:03")
            .verifyComplete();

        mockWebServer.takeRequest();
        assertThat(mockWebServer.takeRequest().getUrl().queryParameter("offset")).isEqualTo("0");
        assertThat(mockWebServer.takeRequest().getUrl().queryParameter("offset")).isEqualTo("2");
    }

    @Test
    void should_resolve_site_only_once() {
        enqueueJson(SITES);
        enqueueJson(EMPTY_PAGE);
        enqueueJson(EMPTY_PAGE);

        sut.getConnectedClients().as(StepVerifier::create).verifyComplete();
        sut.getConnectedClients().as(StepVerifier::create).verifyComplete();

        assertThat(mockWebServer.getRequestCount()).isEqualTo(3);
    }

    @Test
    void should_fail_with_500_and_ask_again_when_site_is_unknown() {
        String otherSite = SITES.replace("Default", "Other").replace("default", "other");
        enqueueJson(otherSite);
        enqueueJson(otherSite);

        sut.getConnectedClients().as(StepVerifier::create)
            .verifyErrorSatisfies(e -> assertStatus(e, HttpStatus.INTERNAL_SERVER_ERROR));
        sut.getConnectedClients().as(StepVerifier::create)
            .verifyErrorSatisfies(e -> assertStatus(e, HttpStatus.INTERNAL_SERVER_ERROR));

        //a failed lookup is not cached - the second call asked again
        assertThat(mockWebServer.getRequestCount()).isEqualTo(2);
    }

    @Test
    void should_fail_with_502_when_api_key_is_rejected() {
        mockWebServer.enqueue(new MockResponse.Builder()
            .code(HttpStatus.UNAUTHORIZED.value())
            .body("{\"message\": \"key secret-key is invalid\"}")
            .build());

        sut.getConnectedClients().as(StepVerifier::create)
            .verifyErrorSatisfies(e -> {
                assertStatus(e, HttpStatus.BAD_GATEWAY);
                //the error body of the gateway is not relayed
                assertThat(e).hasMessage("UniFi answered: 401");
            });
    }

    @Test
    void should_fail_with_504_when_gateway_does_not_answer_in_time() {
        mockWebServer.enqueue(new MockResponse.Builder()
            .headersDelay(RESPONSE_TIMEOUT.toMillis() * 5, TimeUnit.MILLISECONDS)
            .body(SITES)
            .build());

        sut.getConnectedClients().as(StepVerifier::create)
            .verifyErrorSatisfies(e -> assertStatus(e, HttpStatus.GATEWAY_TIMEOUT));
    }

    @Test
    void should_fail_with_502_when_gateway_is_unreachable() {
        mockWebServer.close();

        sut.getConnectedClients().as(StepVerifier::create)
            .verifyErrorSatisfies(e -> assertStatus(e, HttpStatus.BAD_GATEWAY));
    }

    private void enqueueJson(final String body) {
        mockWebServer.enqueue(new MockResponse.Builder()
            .addHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .body(body)
            .build());
    }

    private static void assertStatus(final Throwable throwable, final HttpStatus status) {
        assertThat(throwable).isInstanceOf(UnifiCallException.class);
        assertThat(((UnifiCallException) throwable).getHttpStatus()).isEqualTo(status);
    }
}
