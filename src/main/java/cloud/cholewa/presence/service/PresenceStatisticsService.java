package cloud.cholewa.presence.service;

import cloud.cholewa.presence.config.PresenceProperties;
import cloud.cholewa.presence.database.model.PresenceStatusEntity;
import cloud.cholewa.presence.database.repository.PresenceStatusRepository;
import cloud.cholewa.presence.model.DailyPresenceReport;
import cloud.cholewa.presence.model.HouseReport;
import cloud.cholewa.presence.model.OccupancyInterval;
import cloud.cholewa.presence.model.PresenceInterval;
import cloud.cholewa.presence.model.PresenceStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

//Aggregated reports on top of the intervals: the days of one resident and the occupancy of the
//whole house. Both cover only what was observed - they end at the last check, not at the time of
//the request and not at an end of the range that lies in the future: time nobody has looked at yet
//is neither presence nor absence, and counted in it would make every running day look partly empty.
@Service
@RequiredArgsConstructor
public class PresenceStatisticsService {

    //on top of the absence threshold: the passes are a minute apart and the tracker tolerates up to
    //three minutes between two of them before it starts a grace period over
    private static final Duration OBSERVATION_MARGIN = Duration.ofMinutes(3);

    private final PresenceReportService presenceReportService;
    private final PresenceStatusRepository presenceStatusRepository;
    private final PresenceIntervalCalculator presenceIntervalCalculator;
    private final PresenceStatisticsCalculator presenceStatisticsCalculator;
    private final PresenceProperties presenceProperties;

    //Built on the interval report, so the same rules decide who is known (404) and which range is
    //valid (400). A resident nothing is stored for has no day at all.
    public Mono<DailyPresenceReport> getDailyReport(
        final String name,
        final LocalDateTime from,
        final LocalDateTime to
    ) {
        return presenceReportService.readHistory(name, from, to)
            .map(history -> {
                final LocalDateTime until = history.lastCheckedAt() == null
                    ? null
                    : observedUntil(from, to, history.lastCheckedAt());

                return new DailyPresenceReport(
                    name, from, to, until,
                    until == null
                        ? List.of()
                        : presenceStatisticsCalculator.dailyPresence(history.report().intervals(), from, to, until));
            });
    }

    //The house is occupied while anyone is at home - members who have left the registry since
    //included, their history is part of the house's. The report covers the part of the range the
    //history covers: from the first row ever stored to the last check of anyone. The bounds are read
    //before the rows, and everything one after another - the pool holds two connections, and a
    //pass stored in between only adds presence past the end, which is cut off.
    public Mono<HouseReport> getHouseReport(final LocalDateTime from, final LocalDateTime to) {
        final Optional<ResponseStatusException> violation = ReportRange.violation(from, to);
        if (violation.isPresent()) {
            return Mono.error(violation.get());
        }

        return presenceStatusRepository.findFirstStart()
            .flatMap(firstStart -> presenceStatusRepository.findLastCheck()
                .flatMap(lastCheck -> {
                    final LocalDateTime observedFrom = firstStart.isAfter(from) ? firstStart : from;
                    final LocalDateTime until = observedUntil(observedFrom, to, lastCheck);

                    return until == null
                        ? Mono.<HouseReport>empty()
                        : readHouseReport(from, to, observedFrom, until, lastCheck);
                }))
            //an empty table, or a range outside everything ever observed: not an empty house -
            //nothing is known
            .defaultIfEmpty(new HouseReport(from, to, null, null, List.of(), List.of()));
    }

    private Mono<HouseReport> readHouseReport(
        final LocalDateTime from,
        final LocalDateTime to,
        final LocalDateTime observedFrom,
        final LocalDateTime until,
        final LocalDateTime lastCheck
    ) {
        return presenceStatusRepository.findLatestPerMember()
            .collectList()
            .flatMap(latestRows -> presenceStatusRepository.findPresentBetween(from, to)
                .collectList()
                .map(rows -> {
                    //no row is "the latest of the member" here: open means nothing for a union
                    final List<PresenceInterval> intervals =
                        new ArrayList<>(presenceIntervalCalculator.derive(rows, null, from, to));
                    intervals.addAll(undecidedPresence(latestRows, lastCheck, until));

                    final List<OccupancyInterval> timeline =
                        presenceStatisticsCalculator.occupancy(intervals, observedFrom, until);

                    return new HouseReport(
                        from, to, observedFrom, until, timeline,
                        presenceStatisticsCalculator.dailyOccupancy(timeline, observedFrom, until));
                }));
    }

    //A member who is not seen for a moment stays present: their row is simply not checked while the
    //grace period runs, and only when it runs out is the absence written - dated back to the last
    //sighting. Meanwhile the rows of the others move on, so the last check of the table lies after
    //that row's and the stretch in between would read as nobody at home, although the service still
    //says "present". It is counted as presence until decided otherwise, like the current presence
    //does; should the member turn out to have left, the absence appears in the next report.
    //Only a fresh row is carried on - the last row of a member who left the registry while present
    //is never closed, and would keep the house occupied for good.
    private List<PresenceInterval> undecidedPresence(
        final List<PresenceStatusEntity> latestRows,
        final LocalDateTime lastCheck,
        final LocalDateTime until
    ) {
        final LocalDateTime stillUndecidedSince =
            lastCheck.minus(presenceProperties.absenceThreshold()).minus(OBSERVATION_MARGIN);

        return latestRows.stream()
            .filter(row -> row.status() == PresenceStatus.PRESENT)
            .filter(row -> !row.lastCheckedAt().isBefore(stillUndecidedSince))
            .filter(row -> row.lastCheckedAt().isBefore(until))
            .map(row -> new PresenceInterval(row.lastCheckedAt(), until, true))
            .toList();
    }

    //where the statistics of a range end: at the last check, or at the end of the range when that
    //comes first; null when that leaves nothing after "from" - the range starts after everything
    //that was observed, or ends before anything was
    private static LocalDateTime observedUntil(
        final LocalDateTime from,
        final LocalDateTime to,
        final LocalDateTime lastCheckedAt
    ) {
        final LocalDateTime end = lastCheckedAt.isBefore(to) ? lastCheckedAt : to;

        return from.isBefore(end) ? end : null;
    }
}
