package cloud.cholewa.presence.config;

import cloud.cholewa.presence.client.UnifiClient;
import cloud.cholewa.presence.error.UnifiCallException;
import lombok.SneakyThrows;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.zalando.logbook.Logbook;
import reactor.test.StepVerifier;

import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

//exercises the real beans of AppConfig against an HTTPS server, because the pinning is security code
//that a plain-HTTP client test never touches
class AppConfigTlsTest {

    private static final String SITES = """
        {
            "offset": 0, "limit": 200, "count": 1, "totalCount": 1,
            "data": [{"id": "88f7af54-98f8-306a-a1c7-c9349722b1f6", "internalReference": "default", "name": "Default"}]
        }
        """;

    private static final String EMPTY_PAGE = """
        {"offset": 0, "limit": 200, "count": 0, "totalCount": 0, "data": []}
        """;

    //like the gateway's own certificate, it does not name the address it is reached by
    private final HeldCertificate gatewayCertificate = new HeldCertificate.Builder()
        .commonName("unifi.local")
        .build();

    private MockWebServer mockWebServer;

    @BeforeEach
    void setUp() {
        mockWebServer = new MockWebServer();
    }

    @AfterEach
    void tearDown() {
        mockWebServer.close();
    }

    @Test
    void should_accept_the_pinned_certificate_although_it_does_not_name_the_host() {
        startServerWith(gatewayCertificate);
        enqueueJson(SITES);
        enqueueJson(EMPTY_PAGE);

        clientPinnedTo(gatewayCertificate).getConnectedClients()
            .as(StepVerifier::create)
            .verifyComplete();

        assertThat(mockWebServer.getRequestCount()).isEqualTo(2);
    }

    @Test
    void should_reject_any_other_certificate() {
        HeldCertificate impostor = new HeldCertificate.Builder()
            .commonName("unifi.local")
            .build();
        startServerWith(impostor);
        enqueueJson(SITES);

        clientPinnedTo(gatewayCertificate).getConnectedClients()
            .as(StepVerifier::create)
            .verifyErrorSatisfies(throwable -> {
                assertThat(throwable).isInstanceOf(UnifiCallException.class);
                assertThat(((UnifiCallException) throwable).getHttpStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
            });

        //the handshake failed, so the API key never left the service
        assertThat(mockWebServer.getRequestCount()).isZero();
    }

    @SneakyThrows
    private void startServerWith(final HeldCertificate certificate) {
        HandshakeCertificates handshakeCertificates = new HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build();
        mockWebServer.useHttps(handshakeCertificates.sslSocketFactory());
        mockWebServer.start();
    }

    private UnifiClient clientPinnedTo(final HeldCertificate certificate) {
        UnifiProperties properties = new UnifiProperties(
            "localhost:" + mockWebServer.getPort(),
            "secret-key",
            "Default",
            sha256Fingerprint(certificate),
            Duration.ofSeconds(2),
            Duration.ofSeconds(2)
        );

        AppConfig appConfig = new AppConfig();
        WebClient webClient = appConfig.unifiWebClient(
            WebClient.builder(),
            appConfig.unifiHttpClient(appConfig.unifiConnectionProvider(), Logbook.create(), properties),
            properties
        );

        return new UnifiClient(webClient, properties);
    }

    @SneakyThrows
    private static String sha256Fingerprint(final HeldCertificate certificate) {
        return HexFormat.ofDelimiter(":").formatHex(
            MessageDigest.getInstance("SHA-256").digest(certificate.certificate().getEncoded()));
    }

    private void enqueueJson(final String body) {
        mockWebServer.enqueue(new MockResponse.Builder()
            .addHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .body(body)
            .build());
    }
}
