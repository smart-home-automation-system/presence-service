package cloud.cholewa.presence.service;

import cloud.cholewa.presence.database.model.PresenceStatusEntity;
import cloud.cholewa.presence.database.repository.PresenceStatusRepository;
import cloud.cholewa.presence.model.DailyPresenceReport;
import cloud.cholewa.presence.model.HouseReport;
import cloud.cholewa.presence.model.OccupancyInterval;
import cloud.cholewa.presence.model.PresenceInterval;
import cloud.cholewa.presence.model.PresenceStatus;
import cloud.cholewa.presence.service.PresenceReportService.ResidentHistory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

//Aggregated reports on top of the intervals: the days of one resident and the occupancy of the
//whole house. Both cover only what was observed - from the first stored status to the last check,
//not to the time of the request and not to an end of the range that lies in the future: time
//nobody has looked at is neither presence nor absence, and counted in it would make every running
//day look partly empty.
@Service
@RequiredArgsConstructor
public class PresenceStatisticsService {

    private final PresenceReportService presenceReportService;
    private final PresenceStatusRepository presenceStatusRepository;
    private final PresenceIntervalCalculator presenceIntervalCalculator;
    private final PresenceStatisticsCalculator presenceStatisticsCalculator;
    private final PresenceTracker presenceTracker;

    //Built on the interval report, so the same rules decide who is known (404) and which range is
    //valid (400). A resident nothing is stored for has no day at all.
    public Mono<DailyPresenceReport> getDailyReport(
        final String name,
        final LocalDateTime from,
        final LocalDateTime to
    ) {
        return presenceReportService.readHistory(name, from, to)
            .flatMap(history -> history.lastCheckedAt() == null
                ? Mono.<DailyPresenceReport>empty()
                : presenceStatusRepository.findFirstStartOf(name)
                    .flatMap(firstStart -> Mono.justOrEmpty(dailyReport(name, from, to, history, firstStart))))
            .defaultIfEmpty(new DailyPresenceReport(name, from, to, null, null, List.of()));
    }

    private Optional<DailyPresenceReport> dailyReport(
        final String name,
        final LocalDateTime from,
        final LocalDateTime to,
        final ResidentHistory history,
        final LocalDateTime firstStart
    ) {
        final LocalDateTime observedFrom = firstStart.isAfter(from) ? firstStart : from;
        final LocalDateTime until = observedUntil(observedFrom, to, history.lastCheckedAt());

        return until == null
            ? Optional.empty()
            : Optional.of(new DailyPresenceReport(
                name, from, to, observedFrom, until,
                presenceStatisticsCalculator.dailyPresence(
                    history.report().intervals(), from, to, observedFrom, until)));
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
                        : readHouseReport(from, to, observedFrom, until);
                }))
            //an empty table, or a range outside everything ever observed: not an empty house -
            //nothing is known
            .defaultIfEmpty(new HouseReport(from, to, null, null, List.of(), List.of()));
    }

    private Mono<HouseReport> readHouseReport(
        final LocalDateTime from,
        final LocalDateTime to,
        final LocalDateTime observedFrom,
        final LocalDateTime until
    ) {
        return presenceStatusRepository.findLatestPerMember()
            .collectList()
            .flatMap(latestRows -> presenceStatusRepository.findPresentBetween(from, to)
                .collectList()
                .map(rows -> {
                    //no row is "the latest of the member" here: open means nothing for a union
                    final List<PresenceInterval> intervals =
                        new ArrayList<>(presenceIntervalCalculator.derive(rows, null, from, to));
                    intervals.addAll(undecidedPresence(latestRows, until));

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
    //says "present". It is counted as presence until decided otherwise; should the member turn
    //out to have left, the absence appears in the next report.
    //Who is still present is the tracker's to say, not something to work out from the age of a
    //row: how long a row can stay unchecked depends on when the grace period started, and it
    //starts over after a restart or skipped passes. And the tracker has forgotten a member who
    //left the registry while present - whose last row is never closed, and would otherwise keep
    //the house occupied for good.
    private List<PresenceInterval> undecidedPresence(
        final List<PresenceStatusEntity> latestRows,
        final LocalDateTime until
    ) {
        final Set<String> present = presenceTracker.presentMembers();

        return latestRows.stream()
            .filter(row -> row.status() == PresenceStatus.PRESENT)
            .filter(row -> present.contains(row.memberName()))
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
