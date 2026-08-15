package cloud.cholewa.presence.model.ubiquity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Access(String type) {
}
