package cloud.cholewa.presence.client;

import cloud.cholewa.presence.config.UnifiProperties;
import cloud.cholewa.presence.error.UnifiCallException;
import cloud.cholewa.presence.model.ConnectedClient;
import cloud.cholewa.presence.model.unifi.NetworkClient;
import cloud.cholewa.presence.model.unifi.Site;
import cloud.cholewa.presence.model.unifi.UnifiPage;
import io.netty.handler.timeout.ReadTimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class UnifiClient {

    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String SITES_PATH = "/proxy/network/integration/v1/sites";

    //the largest page the Integration API serves
    private static final int PAGE_SIZE = 200;

    private final WebClient unifiWebClient;
    private final UnifiProperties unifiProperties;

    //the site id never changes, so it is resolved once and kept; a failed lookup is not cached,
    //so the next call simply tries again
    private final Mono<UUID> siteId = Mono.defer(this::resolveSiteId)
        .cache(uuid -> Duration.ofMillis(Long.MAX_VALUE), error -> Duration.ZERO, () -> Duration.ZERO);

    public Flux<ConnectedClient> getConnectedClients() {
        return siteId
            .flatMapMany(uuid -> fetchClients(uuid, 0)
                .expand(page ->
                    hasNext(page) ? fetchClients(uuid, page.offset() + page.count()) : Mono.empty()))
            .flatMapIterable(UnifiPage::data)
            //VPN and Teleport clients carry no MAC address - they are not on the home Wi-Fi
            .filter(client -> client.macAddress() != null)
            .map(client -> new ConnectedClient(
                client.macAddress(), client.name(), client.type(), client.connectedAt()));
    }

    private Mono<UUID> resolveSiteId() {
        return getPage(
            SITES_PATH, 0, new ParameterizedTypeReference<UnifiPage<Site>>() {
            }
        )
            .flatMapIterable(UnifiPage::data)
            .filter(site -> unifiProperties.site().equalsIgnoreCase(site.name())
                || unifiProperties.site().equalsIgnoreCase(site.internalReference()))
            .next()
            .map(Site::id)
            .switchIfEmpty(Mono.error(() -> new UnifiCallException(
                HttpStatus.INTERNAL_SERVER_ERROR, "UniFi site not found: " + unifiProperties.site())))
            .doOnNext(id -> log.info("Resolved UniFi site {} to id {}", unifiProperties.site(), id));
    }

    private Mono<UnifiPage<NetworkClient>> fetchClients(final UUID siteId, final int offset) {
        return getPage(
            SITES_PATH + "/" + siteId + "/clients", offset, new ParameterizedTypeReference<>() {
            }
        );
    }

    private <T> Mono<UnifiPage<T>> getPage(
        final String path,
        final int offset,
        final ParameterizedTypeReference<UnifiPage<T>> typeReference
    ) {
        return unifiWebClient.get()
            .uri(uriBuilder -> uriBuilder
                .path(path)
                .queryParam("offset", offset)
                .queryParam("limit", PAGE_SIZE)
                .build())
            .header(API_KEY_HEADER, unifiProperties.apiKey())
            .accept(MediaType.APPLICATION_JSON)
            .retrieve()
            //only the status is relayed - whatever the gateway writes into an error body stays out of
            //the logs and out of the response
            .onStatus(
                HttpStatusCode::isError, response -> response.releaseBody()
                    .thenReturn(new UnifiCallException(
                        HttpStatus.BAD_GATEWAY,
                        "UniFi answered: " + response.statusCode().value()
                    ))
            )
            .bodyToMono(typeReference)
            //every failure leaves as a UnifiCallException - also the ones after the response headers
            //(a stall or a dropped connection in the middle of the body, an answer that is not the
            //expected JSON), which WebClient does not wrap in a WebClientRequestException
            .onErrorMap(e -> !(e instanceof UnifiCallException), this::toUnifiCallException);
    }

    private UnifiCallException toUnifiCallException(final Throwable throwable) {
        final Throwable cause = NestedExceptionUtils.getMostSpecificCause(throwable);

        //a read timeout means the gateway is there but slow - before the headers or in the body
        if (cause instanceof ReadTimeoutException) {
            return new UnifiCallException(
                HttpStatus.GATEWAY_TIMEOUT,
                "UniFi did not answer within " + unifiProperties.responseTimeout(),
                throwable
            );
        }

        //anything else on the request side (connect timeout, refused, TLS handshake) means the
        //gateway could not be reached at all
        if (throwable instanceof WebClientRequestException) {
            return new UnifiCallException(
                HttpStatus.BAD_GATEWAY, "UniFi unreachable: " + cause.getClass().getSimpleName(), throwable);
        }

        //only the type is named - a decoding error quotes the body it could not read
        return new UnifiCallException(
            HttpStatus.BAD_GATEWAY, "UniFi answer unreadable: " + cause.getClass().getSimpleName(), throwable);
    }

    //count > 0 guards against looping forever on an empty page that still reports a larger total
    private static boolean hasNext(final UnifiPage<?> page) {
        return page.count() > 0 && page.offset() + page.count() < page.totalCount();
    }
}
