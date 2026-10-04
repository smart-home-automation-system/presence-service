package cloud.cholewa.presence.model;

import java.time.LocalDateTime;

//where a registered resident is right now: the latest row of their history. since is the start of
//that row, lastCheckedAt the last pass that confirmed it - how fresh the answer is. Both are null
//for a resident the engine has not stored anything for yet, who is reported as not present
public record ResidentPresence(
    String name,
    boolean present,
    LocalDateTime since,
    LocalDateTime lastCheckedAt
) {
}
