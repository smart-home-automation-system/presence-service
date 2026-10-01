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
import org.zalando.logbook.Logbook;
import org.zalando.logbook.netty.LogbookClientHandler;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;

@Configuration
@EnableConfigurationProperties(UnifiProperties.class)
public class AppConfig {

    @Bean
    ConnectionProvider unifiConnectionProvider() {
        return ConnectionProvider.builder("unifiConnectionProvider")
            .maxConnections(10)
            .build();
    }

    @Bean
    @SneakyThrows
    HttpClient unifiHttpClient(
        final ConnectionProvider unifiConnectionProvider,
        final Logbook logbook,
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
            .doOnConnected(connection -> connection.addHandlerLast(new LogbookClientHandler(logbook)));
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
}
