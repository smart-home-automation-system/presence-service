package cloud.cholewa.presence.client;

import cloud.cholewa.presence.config.UbiquityConfiguration;
import cloud.cholewa.presence.model.ubiquity.Client;
import cloud.cholewa.presence.model.ubiquity.Site;
import cloud.cholewa.presence.model.ubiquity.UbiquityResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Slf4j
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(UbiquityConfiguration.class)
public class UbiquityClient {

    private static final String AUTH = "X-API-Key";

    private final WebClient ubiquityWebClient;
    private final UbiquityConfiguration ubiquityConfiguration;

    public Mono<UbiquityResponse<Site>> getSites() {
        return ubiquityWebClient.get()
            .uri(uriBuilder -> ubiquityConfiguration.getUriBuilder(uriBuilder)
                .path("proxy/network/integration/v1/sites")
                .build())
            .header(AUTH, ubiquityConfiguration.token())
            .accept(MediaType.APPLICATION_JSON)
            .retrieve()
            .bodyToMono(new ParameterizedTypeReference<>() {
            });
    }
    
    public Mono<UbiquityResponse<Client>> getClients() {
        return ubiquityWebClient.get()
            .uri(uriBuilder -> ubiquityConfiguration.getUriBuilder(uriBuilder)
                .path("proxy/network/integration/v1/sites/88f7af54-98f8-306a-a1c7-c9349722b1f6/clients")
                .queryParam("limit", 200)
                .build()
            )
            .header(AUTH, ubiquityConfiguration.token())
            .accept(MediaType.APPLICATION_JSON)
            .retrieve()
            .bodyToMono(new ParameterizedTypeReference<>() {});
    }
}
