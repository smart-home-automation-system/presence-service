package cloud.cholewa.presence.database.repository;

import cloud.cholewa.presence.database.model.PresenceStatusEntity;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

public interface PresenceStatusRepository extends R2dbcRepository<PresenceStatusEntity, Long> {

    //the latest row of every member - the current state, read once at startup
    @Query("""
        SELECT DISTINCT ON (member_name) *
        FROM presence_status
        ORDER BY member_name, started_at DESC, id DESC
        """)
    Flux<PresenceStatusEntity> findLatestPerMember();

    //moves last_checked_at of the member's latest row; answers the number of rows changed, which is
    //0 when the member has no row yet
    @Modifying
    @Query("""
        UPDATE presence_status
        SET last_checked_at = :checkedAt
        WHERE id = (SELECT id
                    FROM presence_status
                    WHERE member_name = :memberName
                    ORDER BY started_at DESC, id DESC
                    LIMIT 1)
        """)
    Mono<Integer> touchLatest(String memberName, LocalDateTime checkedAt);
}
