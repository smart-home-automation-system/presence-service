package cloud.cholewa.presence.database.repository;

import cloud.cholewa.presence.database.model.PresenceStatusEntity;
import cloud.cholewa.presence.model.PresenceStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.r2dbc.test.autoconfigure.DataR2dbcTest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.TestPropertySource;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.List;

import static cloud.cholewa.presence.model.PresenceStatus.ABSENT;
import static cloud.cholewa.presence.model.PresenceStatus.PRESENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

//The one test that runs SQL: the retention delete, against an in-memory H2 - the statement is
//portable, unlike the PostgreSQL-only reads of this repository, which no test executes.
//The table is the one of V1__create_presence_status_table.sql, written out for H2.
@DataR2dbcTest
@TestPropertySource(properties = "spring.r2dbc.url=r2dbc:h2:mem:///presence-retention;DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE")
class PresenceStatusRetentionTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2027, 10, 6, 3, 0);
    private static final LocalDateTime CUTOFF = NOW.minusDays(365);

    @Autowired
    private PresenceStatusRepository sut;

    @Autowired
    private DatabaseClient databaseClient;

    @BeforeEach
    void createTable() {
        databaseClient.sql("DROP TABLE IF EXISTS presence_status").then()
            .then(databaseClient.sql("""
                CREATE TABLE presence_status
                (
                    id              INT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                    member_name     VARCHAR(50) NOT NULL,
                    status          VARCHAR(10) NOT NULL,
                    started_at      TIMESTAMP   NOT NULL,
                    last_checked_at TIMESTAMP   NOT NULL
                )
                """).then())
            .as(StepVerifier::create)
            .verifyComplete();
    }

    //Anna: an old closed presence and absence, then the presence she is in now.
    //Tom: away for two years - one row, started long before the cutoff, checked a minute ago.
    //Gone: left the registry two years ago, the last row never closed and never checked since.
    @Test
    void should_delete_the_rows_last_checked_before_the_cutoff_and_keep_the_newer_and_the_current_ones() {
        save("Anna", PRESENT, CUTOFF.minusDays(30), CUTOFF.minusDays(29));
        save("Anna", ABSENT, CUTOFF.minusDays(29), CUTOFF.minusSeconds(1));
        save("Anna", PRESENT, CUTOFF, CUTOFF);
        save("Anna", ABSENT, CUTOFF.plusDays(1), CUTOFF.plusDays(2));
        save("Anna", PRESENT, NOW.minusHours(8), NOW.minusMinutes(1));
        save("Tom", ABSENT, CUTOFF.minusDays(365), NOW.minusMinutes(1));
        save("Gone", PRESENT, CUTOFF.minusDays(400), CUTOFF.minusDays(365));

        sut.deleteLastCheckedBefore(CUTOFF).as(StepVerifier::create)
            .expectNext(3L)
            .verifyComplete();

        final List<PresenceStatusEntity> left = sut.findAll().collectList().block();

        assertThat(left)
            .extracting(PresenceStatusEntity::memberName, PresenceStatusEntity::status, PresenceStatusEntity::lastCheckedAt)
            .containsExactlyInAnyOrder(
                //last checked exactly at the cutoff: not before it, so it stays
                tuple("Anna", PRESENT, CUTOFF),
                tuple("Anna", ABSENT, CUTOFF.plusDays(2)),
                //the current rows, whatever their start
                tuple("Anna", PRESENT, NOW.minusMinutes(1)),
                tuple("Tom", ABSENT, NOW.minusMinutes(1))
            );
    }

    @Test
    void should_delete_nothing_when_nothing_is_old_enough() {
        save("Anna", PRESENT, NOW.minusHours(8), NOW.minusMinutes(1));

        sut.deleteLastCheckedBefore(CUTOFF).as(StepVerifier::create)
            .expectNext(0L)
            .verifyComplete();

        sut.count().as(StepVerifier::create).expectNext(1L).verifyComplete();
    }

    private void save(
        final String memberName,
        final PresenceStatus status,
        final LocalDateTime startedAt,
        final LocalDateTime lastCheckedAt
    ) {
        sut.save(new PresenceStatusEntity(null, memberName, status, startedAt, lastCheckedAt)).block();
    }
}
