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

@Validated
@ConfigurationProperties("presence")
public record PresenceProperties(
    //how long every device of a member has to stay unseen before the member counts as absent;
    //absorbs phones that drop off the Wi-Fi while asleep
    @DefaultValue("PT10M") @NotNull Duration absenceThreshold,
    //How long the history is kept, counted from the last check of a row; the reports answer at
    //most 366 days, so anything shorter than a year also shortens what they can show.
    //A bare number is days, and a week is the least: a Duration without a unit binds as
    //milliseconds, so "365" would put the cutoff at "now" and the nightly job would delete the
    //whole table, the current rows included - and so would any value shorter than an outage of
    //the detection. Bounded above too: an absurd value would overflow the date arithmetic of the
    //cutoff instead of being refused here, by name
    @DefaultValue("P365D")
    @NotNull
    @DurationUnit(ChronoUnit.DAYS)
    @DurationMin(days = 7)
    @DurationMax(days = 3660)
    Duration retention
) {
}
