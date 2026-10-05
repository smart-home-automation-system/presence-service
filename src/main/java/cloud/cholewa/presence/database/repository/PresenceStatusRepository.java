package cloud.cholewa.presence.database.repository;

import cloud.cholewa.presence.database.model.PresenceStatusEntity;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

public interface PresenceStatusRepository extends R2dbcRepository<PresenceStatusEntity, Long> {

    //the latest row of every member - the current state, read by the engine once at startup and by
    //every request for the current presence. It walks the whole table, which stays small by design
    //(a row per status change, a year of retention); should that change, read only the active
    //names. "Latest" is the
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

    //the periods anyone was at home that touch the range, of every member - also of those who have
    //left the registry since, because the house was occupied all the same. The same pre-filter as
    //in findForReport; the table has no index by time alone and does not need one at its size
    @Query("""
        SELECT *
        FROM presence_status
        WHERE status = 'PRESENT'
          AND started_at < :to
          AND (last_checked_at > :from OR started_at >= :from)
        ORDER BY id
        """)
    Flux<PresenceStatusEntity> findPresentBetween(LocalDateTime from, LocalDateTime to);

    //Where the history starts and how far it reaches: the first status ever stored and the last
    //pass that stored or confirmed anything. Both empty for an empty table - which is why they are
    //not min() and max(): an aggregate answers one row holding NULL, and a null cannot be emitted
    @Query("SELECT started_at FROM presence_status ORDER BY started_at LIMIT 1")
    Mono<LocalDateTime> findFirstStart();

    @Query("SELECT last_checked_at FROM presence_status ORDER BY last_checked_at DESC LIMIT 1")
    Mono<LocalDateTime> findLastCheck();

    //where the history of one member starts; empty for a member nothing is stored for
    @Query("SELECT started_at FROM presence_status WHERE member_name = :memberName ORDER BY started_at LIMIT 1")
    Mono<LocalDateTime> findFirstStartOf(String memberName);

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
