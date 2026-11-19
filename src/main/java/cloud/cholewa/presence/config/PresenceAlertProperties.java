package cloud.cholewa.presence.config;

import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

//When a detection that does not run is told (HAS-476): no pass evaluated for skippedAfter is an alert.
//A bare number is minutes - without the unit "10" would bind as milliseconds and the first skipped pass
//would raise the alert. At least two minutes, so that one skipped pass of the minute never does; at
//most a day, or an outage could go untold for longer than anybody would accept
@Validated
@ConfigurationProperties("presence.alert")
public record PresenceAlertProperties(
    @DefaultValue("PT10M")
    @NotNull
    @DurationUnit(ChronoUnit.MINUTES)
    @DurationMin(minutes = 2)
    @DurationMax(hours = 24)
    Duration skippedAfter
) {
}
