package cloud.cholewa.presence.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

//Judges the detection passes (HAS-476): a pass that never fails needs somebody to say that it does not
//run - on 2026-11-17 a new certificate of the gateway skipped every pass for two days and nobody was told.
//A plain class like the tracker: told "evaluated" or "skipped with this cause" and the time, it answers
//what to publish, and moves only when told that a notice went out.
//
//Judged by whole passes: the alert once no pass was evaluated for skippedAfter, counted from the last
//evaluated pass (from the first skipped one when none was evaluated since the start - a restart inside
//an outage starts the count again), a reminder every REMINDER_EVERY while it lasts, and one return at the
//first evaluated pass after an alert was due - also when it never got through, which the return then says;
//a return that did not get through is offered again by the next evaluated pass, until a new outage begins.
//The episode lives in memory, as the outage of the Shelly of boiler-service
final class PassWatch {

    static final Duration REMINDER_EVERY = Duration.ofHours(1);

    enum Kind { ALERT, REMINDER, RETURN }

    /**
     * What to publish. {@code since} is the last evaluated pass (or the first skipped one), {@code at} the
     * pass that decided it, {@code cause} the latest cause in the words of the service; {@code untold} on a
     * return: the alert was due but never got through.
     */
    record Notice(Kind kind, long episode, Instant since, Instant at, String cause, boolean untold) {

        Duration down() {
            return Duration.between(since, at);
        }
    }

    private final Duration skippedAfter;

    private Instant lastEvaluated;
    private Instant downSince;
    private String cause;
    private long episode;
    private boolean alertDue;
    private Instant toldAt;
    private Notice untoldReturn;

    PassWatch(final Duration skippedAfter) {
        this.skippedAfter = skippedAfter;
    }

    synchronized Optional<Notice> skipped(final Instant now, final String cause) {
        if (downSince == null) {
            downSince = lastEvaluated != null ? lastEvaluated : now;
            episode++;
            untoldReturn = null;
        }
        this.cause = cause;
        if (Duration.between(downSince, now).compareTo(skippedAfter) < 0) {
            return Optional.empty();
        }
        if (toldAt == null) {
            alertDue = true;
            return Optional.of(new Notice(Kind.ALERT, episode, downSince, now, cause, false));
        }
        if (Duration.between(toldAt, now).compareTo(REMINDER_EVERY) >= 0) {
            return Optional.of(new Notice(Kind.REMINDER, episode, downSince, now, cause, false));
        }
        return Optional.empty();
    }

    synchronized Optional<Notice> evaluated(final Instant now) {
        lastEvaluated = now;
        if (downSince == null) {
            return Optional.ofNullable(untoldReturn);
        }
        untoldReturn = alertDue ? new Notice(Kind.RETURN, episode, downSince, now, cause, toldAt == null) : null;
        downSince = null;
        cause = null;
        alertDue = false;
        toldAt = null;
        return Optional.ofNullable(untoldReturn);
    }

    //a notice counts once the broker took it; one of an episode already over changes nothing
    synchronized void told(final Notice notice) {
        if (notice.episode() != episode) {
            return;
        }
        if (notice.kind() == Kind.RETURN) {
            untoldReturn = null;
        } else if (downSince != null) {
            toldAt = notice.at();
        }
    }
}
