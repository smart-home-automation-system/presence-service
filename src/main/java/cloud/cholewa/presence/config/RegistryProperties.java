package cloud.cholewa.presence.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

//the household registry (members and their devices) kept by database-service
@Validated
@ConfigurationProperties("registry")
public record RegistryProperties(
    @NotBlank String baseUrl,
    //well above database-service's pool validation bound (2 s), so a connection being replaced
    //there does not fail the call here
    @DefaultValue("PT5S") @NotNull Duration responseTimeout
) {
}
