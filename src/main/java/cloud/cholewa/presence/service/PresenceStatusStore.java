package cloud.cholewa.presence.service;

import cloud.cholewa.presence.database.model.PresenceStatusEntity;
import cloud.cholewa.presence.database.repository.PresenceStatusRepository;
import cloud.cholewa.presence.model.PresenceDecision;
import cloud.cholewa.presence.model.PresenceDecision.Changed;
import cloud.cholewa.presence.model.PresenceDecision.Confirmed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

//Writes the decisions of the tracker: a row is inserted only when a status changes, a confirmed
//status just moves last_checked_at of the member's latest row. That keeps the table at a few rows
//per member per day, and last_checked_at doubles as the freshness of the data - a gap between it
//and the next started_at is time the member was not watched.
@Slf4j
@Service
@RequiredArgsConstructor
public class PresenceStatusStore {

    private final PresenceStatusRepository presenceStatusRepository;

    public Flux<PresenceStatusEntity> findLatestPerMember() {
        return presenceStatusRepository.findLatestPerMember();
    }

    @Transactional
    public Mono<Void> apply(final PresenceDecision decision) {
        return switch (decision) {
            case Confirmed confirmed -> confirm(confirmed);
            case Changed changed -> change(changed);
        };
    }

    private Mono<Void> confirm(final Confirmed confirmed) {
        return presenceStatusRepository.touchLatest(confirmed.memberName(), confirmed.checkedAt())
            .filter(updated -> updated == 0)
            //the tracker knows a status the table has no row for (rows removed by hand) - the
            //period is opened again instead of confirming into nothing
            .flatMap(nothingUpdated -> {
                log.warn("No presence row to confirm for {}, opening a new one", confirmed.memberName());
                return presenceStatusRepository.save(new PresenceStatusEntity(
                    null, confirmed.memberName(), confirmed.status(), confirmed.checkedAt(), confirmed.checkedAt()));
            })
            .then();
    }

    private Mono<Void> change(final Changed changed) {
        return presenceStatusRepository.save(new PresenceStatusEntity(
                null, changed.memberName(), changed.status(), changed.startedAt(), changed.checkedAt()))
            .doOnNext(saved -> log.info("{} is {} since {}", saved.memberName(), saved.status(), saved.startedAt()))
            .then();
    }
}
