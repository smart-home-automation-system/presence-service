package cloud.cholewa.presence.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

//the retention decides what the nightly job deletes, so what it binds to is worth pinning: a value
//that puts the cutoff at "now" would delete the whole table, the current rows included
class PresencePropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
        .withUserConfiguration(Config.class);

    @Test
    void should_keep_the_history_for_a_year_by_default() {
        contextRunner.run(context -> assertThat(context.getBean(PresenceProperties.class).retention())
            .isEqualTo(Duration.ofDays(365)));
    }

    //without the unit a bare number would be milliseconds
    @Test
    void should_read_a_bare_number_as_days() {
        contextRunner.withPropertyValues("presence.retention=90")
            .run(context -> assertThat(context.getBean(PresenceProperties.class).retention())
                .isEqualTo(Duration.ofDays(90)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"P0D", "PT1H", "P6D", "-P30D", "0", "999999999999"})
    void should_refuse_to_start_with_a_retention_shorter_than_a_week_or_absurdly_long(final String retention) {
        contextRunner.withPropertyValues("presence.retention=" + retention)
            .run(context -> assertThat(context).hasFailed());
    }

    @Configuration
    @EnableConfigurationProperties(PresenceProperties.class)
    static class Config {
    }
}
