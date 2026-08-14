package cloud.cholewa.presence.api;

import cloud.cholewa.presence.model.ubiquity.Site;
import cloud.cholewa.presence.model.ubiquity.UbiquityResponse;
import cloud.cholewa.presence.service.PresenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@Slf4j
@RestController
@RequiredArgsConstructor
public class PresenceController {
    
    private final PresenceService presenceService;
    
    @GetMapping
    Mono<UbiquityResponse<Site>> getSites() {
        return presenceService.getSites();
    }
}
