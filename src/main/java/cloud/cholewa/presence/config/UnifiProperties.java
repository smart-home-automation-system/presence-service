package cloud.cholewa.presence.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties("unifi")
public record UnifiProperties(
    @NotBlank String host,
    @NotBlank String apiKey,
    @DefaultValue("Default") @NotBlank String site,
    @NotBlank String certificateFingerprint,
    @DefaultValue("PT5S") Duration connectTimeout,
    @DefaultValue("PT10S") Duration responseTimeout
) {

}
