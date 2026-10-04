package cloud.cholewa.presence.database.repository;

import cloud.cholewa.presence.database.model.PresenceStatusEntity;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

public interface PresenceStatusRepository extends R2dbcRepository<PresenceStatusEntity, Long> {

    //the latest row of every member - the current state, read once at startup. "Latest" is the
    //highest id, not the latest started_at: rows are inserted in the order the statuses changed,
    //while started_at is local time and can run backwards on the night the clocks go back
    @Query("""
        SELECT DISTINCT ON (member_name) *
        FROM presence_status
        ORDER BY member_name, id DESC
        """)
    Flux<PresenceStatusEntity> findLatestPerMember();

    //What a report of one member is built from, oldest first: the periods at home that touch the
    //range, and the member's newest row whatever its status and time - always the last one answered,
    //so an empty answer means a member without a history. One statement on purpose: read apart, a
    //status stored in between would make the newest row and the periods disagree about what is
    //still going on. The range rule is only a pre-filter here, PresenceIntervalCalculator owns it.
    //The status is a literal - nothing in the repository runs against a database, so the query does
    //not lean on how an enum parameter is bound
    @Query("""
        SELECT *
        FROM presence_status
        WHERE member_name = :memberName
          AND ((status = 'PRESENT'
                AND started_at < :to
                AND (last_checked_at > :from OR started_at >= :from))
            OR id = (SELECT max(id) FROM presence_status WHERE member_name = :memberName))
        ORDER BY id
        """)
    Flux<PresenceStatusEntity> findForReport(String memberName, LocalDateTime from, LocalDateTime to);

    //moves last_checked_at of the member's latest row; answers the number of rows changed, which is
    //0 when the member has no row yet
    @Modifying
    @Query("""
        UPDATE presence_status
        SET last_checked_at = :checkedAt
        WHERE id = (SELECT id
                    FROM presence_status
                    WHERE member_name = :memberName
                    ORDER BY id DESC
                    LIMIT 1)
        """)
    Mono<Integer> touchLatest(String memberName, LocalDateTime checkedAt);
}
