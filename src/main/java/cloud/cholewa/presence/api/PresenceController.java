package cloud.cholewa.presence.api;

import cloud.cholewa.presence.model.ConnectedClient;
import cloud.cholewa.presence.service.PresenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequiredArgsConstructor
public class PresenceController {

    private final PresenceService presenceService;

    @GetMapping("/clients")
    Flux<ConnectedClient> getConnectedClients() {
        return presenceService.getConnectedClients();
    }
}
