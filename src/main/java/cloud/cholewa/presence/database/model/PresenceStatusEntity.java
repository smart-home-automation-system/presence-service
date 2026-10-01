package cloud.cholewa.presence.database.model;

import cloud.cholewa.presence.model.PresenceStatus;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Table("presence_status")
public record PresenceStatusEntity(
    @Id
    Long id,
    String memberName,
    PresenceStatus status,
    LocalDateTime startedAt,
    LocalDateTime lastCheckedAt
) {
}
