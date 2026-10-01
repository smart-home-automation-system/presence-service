package cloud.cholewa.presence.model.unifi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record UnifiPage<T>(int offset, int limit, int count, int totalCount, List<T> data) {
}
