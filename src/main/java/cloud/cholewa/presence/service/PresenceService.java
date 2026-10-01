package cloud.cholewa.presence.service;

import cloud.cholewa.presence.client.UnifiClient;
import cloud.cholewa.presence.model.ConnectedClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
@RequiredArgsConstructor
public class PresenceService {

    private final UnifiClient unifiClient;

    public Flux<ConnectedClient> getConnectedClients() {
        return unifiClient.getConnectedClients();
    }
}
