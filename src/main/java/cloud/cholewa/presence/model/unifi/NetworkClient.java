package cloud.cholewa.presence.model.unifi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.OffsetDateTime;

@JsonIgnoreProperties(ignoreUnknown = true)
public record NetworkClient(
    String type,
    String name,
    String macAddress,
    OffsetDateTime connectedAt
) {
}
