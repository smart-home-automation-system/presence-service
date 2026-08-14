package cloud.cholewa.presence.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import org.zalando.logbook.Logbook;
import org.zalando.logbook.netty.LogbookClientHandler;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

@Configuration
@RequiredArgsConstructor
public class AppConfig {

    @Bean
    ConnectionProvider ubiquityConnectionProvider() {
        return ConnectionProvider.builder("ubiquityConnectionProvider")
            .maxConnections(10)
            .build();
    }

    @Bean
    @SneakyThrows
    HttpClient ubiquityHttpClient(final ConnectionProvider ubiquityConnectionProvider, final Logbook logbook) {
        SslContext sslContext = SslContextBuilder.forClient()
            .trustManager(InsecureTrustManagerFactory.INSTANCE)
            .build();

        return HttpClient.create(ubiquityConnectionProvider)
            .secure(sslContextSpec -> sslContextSpec.sslContext(sslContext))
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
            .doOnConnected(connection -> connection.addHandlerLast(new LogbookClientHandler(logbook)));
    }

    @Bean
    WebClient ubiquityWebClient(final WebClient.Builder webClientBuilder, final HttpClient ubiquityHttpClient) {
        return webClientBuilder
            .clientConnector(new ReactorClientHttpConnector(ubiquityHttpClient))
            .build();
    }
}
