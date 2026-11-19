package cloud.cholewa.presence.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

//the bound decides when a detection that does not run is told (HAS-476): bound as milliseconds the
//first skipped pass would raise the alert
class PresenceAlertPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
        .withUserConfiguration(Config.class);

    @Test
    void should_alert_after_ten_minutes_by_default() {
        contextRunner.run(context -> assertThat(context.getBean(PresenceAlertProperties.class).skippedAfter())
            .isEqualTo(Duration.ofMinutes(10)));
    }

    @Test
    void should_read_a_bare_number_as_minutes() {
        contextRunner.withPropertyValues("presence.alert.skipped-after=15")
            .run(context -> assertThat(context.getBean(PresenceAlertProperties.class).skippedAfter())
                .isEqualTo(Duration.ofMinutes(15)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "1", "PT90S", "-PT10M", "P2D"})
    void should_refuse_a_bound_a_single_skipped_pass_reaches_or_absurdly_long(final String skippedAfter) {
        contextRunner.withPropertyValues("presence.alert.skipped-after=" + skippedAfter)
            .run(context -> assertThat(context).hasFailed());
    }

    @Configuration
    @EnableConfigurationProperties(PresenceAlertProperties.class)
    static class Config {
    }
}
