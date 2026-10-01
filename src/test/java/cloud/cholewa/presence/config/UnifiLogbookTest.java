package cloud.cholewa.presence.config;

import cloud.cholewa.presence.client.UnifiClient;
import lombok.SneakyThrows;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.test.StepVerifier;

import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

//The gateway is polled every minute, so what its client writes to the log matters twice: the API
//key must never appear, and the answer - tens of kilobytes of client list - must stay out. Both
//depend on wiring (the autoconfigured header filter and sink reused by a hand-built Logbook), so
//they are checked on the real context against an HTTPS server.
@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
class UnifiLogbookTest {

    private static final String API_KEY = "key-that-must-not-be-logged";
    private static final String MAC_IN_THE_ANSWER = "0a:1b:2c:3d:4e:5f";

    private static final HeldCertificate CERTIFICATE = new HeldCertificate.Builder().commonName("unifi.local").build();
    private static final MockWebServer MOCK_WEB_SERVER = startServer();

    @Autowired
    private UnifiClient unifiClient;

    @DynamicPropertySource
    static void unifiProperties(final DynamicPropertyRegistry registry) {
        registry.add("unifi.host", () -> "localhost:" + MOCK_WEB_SERVER.getPort());
        registry.add("unifi.api-key", () -> API_KEY);
        registry.add("unifi.certificate-fingerprint", UnifiLogbookTest::fingerprint);
    }

    @AfterAll
    static void tearDown() {
        MOCK_WEB_SERVER.close();
    }

    @Test
    void should_log_gateway_calls_with_the_key_obfuscated_and_without_bodies(final CapturedOutput output) {
        enqueueJson("""
            {
                "offset": 0, "limit": 200, "count": 1, "totalCount": 1,
                "data": [{"id": "88f7af54-98f8-306a-a1c7-c9349722b1f6", "internalReference": "default", "name": "Default"}]
            }
            """);
        enqueueJson("""
            {
                "offset": 0, "limit": 200, "count": 1, "totalCount": 1,
                "data": [{"type": "WIRELESS", "name": "Phone", "macAddress": "%s"}]
            }
            """.formatted(MAC_IN_THE_ANSWER));

        unifiClient.getConnectedClients()
            .as(StepVerifier::create)
            .expectNextCount(1)
            .verifyComplete();

        //the calls are logged...
        assertThat(output).contains("/proxy/network/integration/v1/sites");
        assertThat(output).contains("X-API-Key: XXX");
        //...without the key and without the answer
        assertThat(output).doesNotContain(API_KEY);
        assertThat(output).doesNotContain(MAC_IN_THE_ANSWER);
    }

    @SneakyThrows
    private static MockWebServer startServer() {
        final MockWebServer server = new MockWebServer();
        server.useHttps(new HandshakeCertificates.Builder().heldCertificate(CERTIFICATE).build().sslSocketFactory());
        server.start();
        return server;
    }

    @SneakyThrows
    private static String fingerprint() {
        return HexFormat.ofDelimiter(":").formatHex(
            MessageDigest.getInstance("SHA-256").digest(CERTIFICATE.certificate().getEncoded()));
    }

    private static void enqueueJson(final String body) {
        MOCK_WEB_SERVER.enqueue(new MockResponse.Builder()
            .addHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .body(body)
            .build());
    }
}
