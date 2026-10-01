package cloud.cholewa.presence.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties("presence")
public record PresenceProperties(
    //how long every device of a member has to stay unseen before the member counts as absent;
    //absorbs phones that drop off the Wi-Fi while asleep
    @DefaultValue("PT10M") @NotNull Duration absenceThreshold
) {
}
