package dev.privacyagent.audit;

import dev.privacyagent.config.InternalTokenFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.slf4j.MDC;

/** Append-only audit record. Messages and metadata hold counts, types and masked values, never document text. */
@Entity
@Table(name = "audit_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditEvent {

    @Id
    private UUID id;

    @Column(name = "document_id")
    private UUID documentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false)
    private AuditEventType eventType;

    @Column(nullable = false)
    private String message;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Signed-in user who triggered the event (from the request context); null for system events. */
    @Column(length = 64)
    private String actor;

    static AuditEvent of(UUID documentId, AuditEventType type, String message, Map<String, Object> metadata,
                         Instant now) {
        var e = new AuditEvent();
        e.id = UUID.randomUUID();
        e.documentId = documentId;
        e.eventType = type;
        e.message = message.length() > 500 ? message.substring(0, 500) : message;
        e.metadata = metadata == null ? Map.of() : metadata;
        e.createdAt = now;
        e.actor = MDC.get(InternalTokenFilter.MDC_USER);
        return e;
    }
}
