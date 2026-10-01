package cloud.cholewa.presence.model.unifi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Site(UUID id, String internalReference, String name) {
}
