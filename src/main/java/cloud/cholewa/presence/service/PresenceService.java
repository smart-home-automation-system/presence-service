package cloud.cholewa.presence.service;

import cloud.cholewa.presence.client.UnifiClient;
import cloud.cholewa.presence.model.ConnectedClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Slf4j
@Service
@RequiredArgsConstructor
public class PresenceService {

    private final UnifiClient unifiClient;

    public Flux<ConnectedClient> getConnectedClients() {
        return unifiClient.getConnectedClients();
    }
}
