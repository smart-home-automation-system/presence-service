package cloud.cholewa.presence.model.ubiquity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.OffsetDateTime;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Client(
    String type,
    UUID id,
    String name,
    OffsetDateTime connectedAt,
    String ipAddress,
    String macAddress,
    UUID uplinkDeviceId,
    Access access
) {
}
