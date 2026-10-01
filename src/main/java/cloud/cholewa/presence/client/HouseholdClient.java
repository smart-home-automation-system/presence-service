package cloud.cholewa.presence.client;

import cloud.cholewa.home.model.HouseholdMember;
import cloud.cholewa.home.model.MemberPhoneDetails;
import cloud.cholewa.presence.config.RegistryProperties;
import cloud.cholewa.presence.error.RegistryCallException;
import cloud.cholewa.presence.model.Member;
import io.netty.handler.timeout.ReadTimeoutException;
import lombok.RequiredArgsConstructor;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class HouseholdClient {

    private static final String HOUSEHOLD_PATH = "/home/household";

    private final WebClient registryWebClient;
    private final RegistryProperties registryProperties;

    //the active members with their devices; an empty registry is a normal answer (200 []), not an
    //error - only a failed call is
    public Mono<List<Member>> getActiveMembers() {
        return registryWebClient.get()
            .uri(HOUSEHOLD_PATH)
            .accept(MediaType.APPLICATION_JSON)
            .retrieve()
            .onStatus(HttpStatusCode::isError, response -> response.releaseBody()
                .thenReturn(new RegistryCallException(
                    "database-service answered: " + response.statusCode().value())))
            .bodyToFlux(HouseholdMember.class)
            .filter(member -> Boolean.TRUE.equals(member.getActive()))
            .map(HouseholdClient::toMember)
            .collectList()
            //every failure leaves as a RegistryCallException, also the ones after the response
            //headers, which WebClient does not wrap in a WebClientRequestException
            .onErrorMap(e -> !(e instanceof RegistryCallException), this::toRegistryCallException);
    }

    //a member without devices stays in the list: they can never be seen, so they are simply absent
    private static Member toMember(final HouseholdMember householdMember) {
        final List<MemberPhoneDetails> devices =
            Objects.requireNonNullElse(householdMember.getDevices(), List.of());

        final Set<String> macAddresses = devices.stream()
            .map(MemberPhoneDetails::getMac)
            .filter(Objects::nonNull)
            //the registry stores them lowercase already; normalised anyway, because a miss here
            //would silently read as "not at home"
            .map(mac -> mac.toLowerCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());

        return new Member(householdMember.getName(), macAddresses);
    }

    private RegistryCallException toRegistryCallException(final Throwable throwable) {
        final Throwable cause = NestedExceptionUtils.getMostSpecificCause(throwable);

        if (cause instanceof ReadTimeoutException) {
            return new RegistryCallException(
                "database-service did not answer within " + registryProperties.responseTimeout(), throwable);
        }

        return new RegistryCallException(
            "database-service call failed: " + cause.getClass().getSimpleName(), throwable);
    }
}
