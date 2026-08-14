package cloud.cholewa.presence.model.ubiquity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record UbiquityResponse<T>(int offset, int limit, int count, int totalCount, List<T> data) {
}
