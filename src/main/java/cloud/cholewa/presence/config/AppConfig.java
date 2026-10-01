package cloud.cholewa.presence.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.FingerprintTrustManagerFactory;
import lombok.SneakyThrows;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import org.zalando.logbook.CorrelationId;
import org.zalando.logbook.HeaderFilter;
import org.zalando.logbook.Logbook;
import org.zalando.logbook.Sink;
import org.zalando.logbook.core.WithoutBodyStrategy;
import org.zalando.logbook.netty.LogbookClientHandler;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;

@Configuration
@EnableConfigurationProperties({UnifiProperties.class, RegistryProperties.class, PresenceProperties.class})
public class AppConfig {

    //Spring infers only close() and shutdown() as destroy methods, so the pool is named explicitly
    @Bean(destroyMethod = "dispose")
    ConnectionProvider unifiConnectionProvider() {
        return ConnectionProvider.builder("unifiConnectionProvider")
            .maxConnections(10)
            .build();
    }

    @Bean
    @SneakyThrows
    HttpClient unifiHttpClient(
        final ConnectionProvider unifiConnectionProvider,
        final CorrelationId correlationId,
        final HeaderFilter headerFilter,
        final Sink sink,
        final UnifiProperties unifiProperties
    ) {
        //the gateway serves a self-signed certificate - trust exactly that one, pinned by its SHA-256
        //fingerprint, instead of trusting everything
        SslContext sslContext = SslContextBuilder.forClient()
            .trustManager(FingerprintTrustManagerFactory.builder("SHA-256")
                .fingerprints(unifiProperties.certificateFingerprint())
                .build()
            )
            .build();

        final Logbook unifiLogbook = unifiLogbook(correlationId, headerFilter, sink);

        return HttpClient.create(unifiConnectionProvider)
            .secure(sslContextSpec -> sslContextSpec.sslContext(sslContext)
                //the certificate does not name the LAN address it is reached by; the pinned
                //fingerprint is a stronger check than the hostname, so hostname verification is off
                .handlerConfigurator(sslHandler -> {
                    SSLEngine engine = sslHandler.engine();
                    SSLParameters parameters = engine.getSSLParameters();
                    //an empty string, not null - the JDK ignores a null here and keeps verifying
                    parameters.setEndpointIdentificationAlgorithm("");
                    engine.setSSLParameters(parameters);
                })
            )
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) unifiProperties.connectTimeout().toMillis())
            .responseTimeout(unifiProperties.responseTimeout())
            .doOnConnected(connection -> connection.addHandlerLast(new LogbookClientHandler(unifiLogbook)));
    }

    @Bean
    WebClient unifiWebClient(
        final WebClient.Builder webClientBuilder,
        final HttpClient unifiHttpClient,
        final UnifiProperties unifiProperties
    ) {
        return webClientBuilder
            .baseUrl("https://" + unifiProperties.host())
            .clientConnector(new ReactorClientHttpConnector(unifiHttpClient))
            .build();
    }

    @Bean
    HttpClient registryHttpClient(final Logbook logbook, final RegistryProperties registryProperties) {
        return HttpClient.create()
            .responseTimeout(registryProperties.responseTimeout())
            .doOnConnected(connection -> connection.addHandlerLast(new LogbookClientHandler(logbook)));
    }

    @Bean
    WebClient registryWebClient(
        final WebClient.Builder webClientBuilder,
        final HttpClient registryHttpClient,
        final RegistryProperties registryProperties
    ) {
        return webClientBuilder
            .baseUrl(registryProperties.baseUrl())
            .clientConnector(new ReactorClientHttpConnector(registryHttpClient))
            .build();
    }

    //The calls to the gateway are logged without bodies - request line, status and headers only.
    //The client list is polled every minute and one answer is tens of kilobytes: far above the
    //16 KB at which the container runtime splits a log line, after which Loki can no longer parse
    //it. Everything else in the service keeps the org default of full bodies. Built from the
    //autoconfigured correlation id, header filter and sink, so X-API-Key stays obfuscated and the format is the
    //same; deliberately not a bean - a Logbook bean would make the autoconfigured one back off.
    private static Logbook unifiLogbook(
        final CorrelationId correlationId,
        final HeaderFilter headerFilter,
        final Sink sink
    ) {
        return Logbook.builder()
            .correlationId(correlationId)
            .headerFilter(headerFilter)
            .strategy(new WithoutBodyStrategy())
            .sink(sink)
            .build();
    }
}
