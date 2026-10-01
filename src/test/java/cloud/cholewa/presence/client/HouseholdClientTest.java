package cloud.cholewa.presence.client;

import cloud.cholewa.presence.config.RegistryProperties;
import cloud.cholewa.presence.error.RegistryCallException;
import cloud.cholewa.presence.model.Member;
import lombok.SneakyThrows;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.SocketEffect;
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
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class HouseholdClientTest {

    private static final Duration RESPONSE_TIMEOUT = Duration.ofMillis(300);

    private MockWebServer mockWebServer;
    private HouseholdClient sut;

    @BeforeEach
    @SneakyThrows
    void setUp() {
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        final String baseUrl = "http://localhost:" + mockWebServer.getPort();
        final WebClient webClient = WebClient.builder()
            .baseUrl(baseUrl)
            .clientConnector(new ReactorClientHttpConnector(HttpClient.create().responseTimeout(RESPONSE_TIMEOUT)))
            .build();

        sut = new HouseholdClient(webClient, new RegistryProperties(baseUrl, RESPONSE_TIMEOUT));
    }

    @AfterEach
    void tearDown() {
        mockWebServer.close();
    }

    @Test
    @SneakyThrows
    void should_return_members_with_their_devices() {
        enqueueJson("""
            [
                {
                    "name": "Anna", "phone": "+48500000001", "active": true,
                    "devices": [
                        {"name": "phone", "mac": "02:00:00:00:00:01"},
                        {"name": "watch", "mac": "02:00:00:00:00:02"}
                    ]
                },
                {
                    "name": "Jan", "phone": "+48500000002", "active": true,
                    "devices": [{"name": "phone", "mac": "02:00:00:00:00:03"}]
                }
            ]
            """);

        sut.getActiveMembers()
            .as(StepVerifier::create)
            .assertNext(members -> assertThat(members).containsExactly(
                new Member("Anna", Set.of("02:00:00:00:00:01", "02:00:00:00:00:02")),
                new Member("Jan", Set.of("02:00:00:00:00:03"))
            ))
            .verifyComplete();

        assertThat(mockWebServer.takeRequest().getUrl().encodedPath()).isEqualTo("/home/household");
    }

    //an empty registry is a normal answer since database-service 0.6.1, not an error
    @Test
    void should_return_an_empty_list_for_an_empty_registry() {
        enqueueJson("[]");

        sut.getActiveMembers()
            .as(StepVerifier::create)
            .assertNext(members -> assertThat(members).isEmpty())
            .verifyComplete();
    }

    @Test
    void should_skip_inactive_members() {
        enqueueJson("""
            [
                {"name": "Anna", "phone": "+48500000001", "active": true,
                 "devices": [{"name": "phone", "mac": "02:00:00:00:00:01"}]},
                {"name": "Jan", "phone": "+48500000002", "active": false,
                 "devices": [{"name": "phone", "mac": "02:00:00:00:00:03"}]}
            ]
            """);

        sut.getActiveMembers()
            .as(StepVerifier::create)
            .assertNext(members -> assertThat(members).extracting(Member::name).containsExactly("Anna"))
            .verifyComplete();
    }

    @Test
    void should_keep_a_member_without_devices() {
        enqueueJson("""
            [
                {"name": "Anna", "phone": "+48500000001", "active": true, "devices": []},
                {"name": "Jan", "phone": "+48500000002", "active": true}
            ]
            """);

        sut.getActiveMembers()
            .as(StepVerifier::create)
            .assertNext(members -> assertThat(members)
                .containsExactly(new Member("Anna", Set.of()), new Member("Jan", Set.of())))
            .verifyComplete();
    }

    @Test
    void should_fail_when_database_service_answers_with_an_error() {
        mockWebServer.enqueue(new MockResponse.Builder()
            .code(HttpStatus.INTERNAL_SERVER_ERROR.value())
            .body("{\"errors\": [{\"message\": \"boom\"}]}")
            .build());

        sut.getActiveMembers().as(StepVerifier::create)
            .verifyErrorSatisfies(e -> assertThat(e)
                .isInstanceOf(RegistryCallException.class)
                .hasMessage("database-service answered: 500"));
    }

    @Test
    void should_fail_when_database_service_does_not_answer_in_time() {
        mockWebServer.enqueue(new MockResponse.Builder()
            .headersDelay(RESPONSE_TIMEOUT.toMillis() * 5, TimeUnit.MILLISECONDS)
            .body("[]")
            .build());

        sut.getActiveMembers().as(StepVerifier::create)
            .verifyErrorSatisfies(e -> assertThat(e)
                .isInstanceOf(RegistryCallException.class)
                .hasMessageStartingWith("database-service did not answer within"));
    }

    @Test
    void should_fail_when_the_connection_is_dropped_in_the_middle_of_the_body() {
        mockWebServer.enqueue(new MockResponse.Builder()
            .addHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .body("[]")
            .onResponseBody(new SocketEffect.CloseSocket())
            .build());

        sut.getActiveMembers().as(StepVerifier::create)
            .verifyError(RegistryCallException.class);
    }

    @Test
    void should_fail_when_database_service_is_unreachable() {
        mockWebServer.close();

        sut.getActiveMembers().as(StepVerifier::create)
            .verifyError(RegistryCallException.class);
    }

    private void enqueueJson(final String body) {
        mockWebServer.enqueue(new MockResponse.Builder()
            .addHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .body(body)
            .build());
    }
}
