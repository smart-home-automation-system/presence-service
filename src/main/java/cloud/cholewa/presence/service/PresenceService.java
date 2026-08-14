package cloud.cholewa.presence.service;

import cloud.cholewa.presence.client.UbiquityClient;
import cloud.cholewa.presence.model.ubiquity.Site;
import cloud.cholewa.presence.model.ubiquity.UbiquityResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Slf4j
@Service
@RequiredArgsConstructor
public class PresenceService {
    
    private final UbiquityClient ubiquityClient;

    public Mono<UbiquityResponse<Site>> getSites() {
        return ubiquityClient.getSites();
    }
}
