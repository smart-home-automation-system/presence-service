package cloud.cholewa.presence.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.web.util.UriBuilder;

@ConfigurationProperties("ubiquity")
public record UbiquityConfiguration(
    String scheme,
    String address,
    int port,
    String token
) {
    public UriBuilder getUriBuilder(final UriBuilder builder) {
        return builder.scheme(scheme).host(address);
    }
}
