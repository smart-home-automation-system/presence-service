package cloud.cholewa.presence.model;

import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Objects;

public record ConnectedClient(
    String macAddress,
    String name,
    String type,
    OffsetDateTime connectedAt
) {
    //the registry in database-service stores MAC addresses lowercase, so the address is normalised
    //once, here, and every comparison downstream can be a plain equals
    public ConnectedClient {
        macAddress = Objects.requireNonNull(macAddress, "macAddress").toLowerCase(Locale.ROOT);
    }
}
