package cloud.cholewa.presence.service;

import cloud.cholewa.presence.client.HouseholdClient;
import cloud.cholewa.presence.database.model.PresenceStatusEntity;
import cloud.cholewa.presence.database.repository.PresenceStatusRepository;
import cloud.cholewa.presence.model.Member;
import cloud.cholewa.presence.model.PresenceReport;
import cloud.cholewa.presence.model.PresenceStatus;
import cloud.cholewa.presence.model.ResidentPresence;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.text.Collator;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

//The read side of the presence history: who is at home now and when one resident was.
@Service
@RequiredArgsConstructor
public class PresenceReportService {

    //the retention horizon of the history - nothing older is kept, so nothing longer is answered
    private static final int MAX_RANGE_YEARS = 1;
    private static final Locale NAME_ORDER = Locale.forLanguageTag("pl");

    private final HouseholdClient householdClient;
    private final PresenceStatusRepository presenceStatusRepository;
    private final PresenceIntervalCalculator presenceIntervalCalculator;

    //The active registry decides who is listed, the latest row of each of them what is answered:
    //a member removed or deactivated there is no longer watched, so their last row would say
    //"present" forever. A member the engine has stored nothing for yet is listed as not present.
    public Flux<ResidentPresence> getCurrentPresence() {
        return Mono.zip(
                householdClient.getActiveMembers(),
                presenceStatusRepository.findLatestPerMember()
                    .collectMap(PresenceStatusEntity::memberName, Function.identity())
            )
            .flatMapMany(read -> Flux.fromStream(() -> read.getT1().stream()
                .map(Member::name)
                //the names are Polish: in plain String order Łukasz comes after Zofia
                .sorted(Collator.getInstance(NAME_ORDER))
                .map(name -> toResidentPresence(name, read.getT2()))));
    }

    //The range is local time, like the stored rows, so it is compared as it is.
    public Mono<PresenceReport> getReport(final String name, final LocalDateTime from, final LocalDateTime to) {
        if (!from.isBefore(to)) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "from must be before to"));
        }
        if (from.plusYears(MAX_RANGE_YEARS).isBefore(to)) {
            return Mono.error(new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "The range must not be longer than " + MAX_RANGE_YEARS + " year"));
        }

        //the newest row of the member is always the last one read, whatever the range
        return presenceStatusRepository.findForReport(name, from, to)
            .collectList()
            .flatMap(rows -> rows.isEmpty()
                ? reportWithoutHistory(name, from, to)
                : Mono.just(new PresenceReport(
                    name, from, to, presenceIntervalCalculator.derive(rows, rows.getLast().id(), from, to))));
    }

    //No row at all: an active member the engine has not stored yet has an empty report, anyone else
    //is unknown. The registry is asked only here, so a member with a history - also one who left
    //the registry - is answered while database-service is down.
    private Mono<PresenceReport> reportWithoutHistory(
        final String name,
        final LocalDateTime from,
        final LocalDateTime to
    ) {
        return householdClient.getActiveMembers()
            .filter(members -> members.stream().anyMatch(member -> member.name().equals(name)))
            .map(members -> new PresenceReport(name, from, to, List.of()))
            .switchIfEmpty(Mono.error(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Unknown resident: " + name)));
    }

    private static ResidentPresence toResidentPresence(
        final String name,
        final Map<String, PresenceStatusEntity> latestRows
    ) {
        final PresenceStatusEntity latest = latestRows.get(name);

        return latest == null
            ? new ResidentPresence(name, false, null, null)
            : new ResidentPresence(
                name, latest.status() == PresenceStatus.PRESENT, latest.startedAt(), latest.lastCheckedAt());
    }
}
