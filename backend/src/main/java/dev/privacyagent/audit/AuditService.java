package dev.privacyagent.audit;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditEventRepository repository;
    private final Clock clock;

    /** Records an event in the caller's transaction, so it commits (or rolls back) with the business change. */
    @Transactional
    public AuditEvent record(UUID documentId, AuditEventType type, String message, Map<String, Object> metadata) {
        return repository.save(AuditEvent.of(documentId, type, message, metadata, clock.instant()));
    }

    /** Records an event even if the caller's transaction is rolling back (used for failures). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditEvent recordIndependently(UUID documentId, AuditEventType type, String message,
                                          Map<String, Object> metadata) {
        return repository.save(AuditEvent.of(documentId, type, message, metadata, clock.instant()));
    }

    @Transactional(readOnly = true)
    public List<AuditEvent> forDocument(UUID documentId) {
        return repository.findByDocumentIdOrderByCreatedAtAscIdAsc(documentId);
    }

    /**
     * Masks a detected value for the audit trail so the trail itself does not become a copy of the PII:
     * "Markus Weber" becomes "Ma•••••••••• (12 chars)".
     */
    public static String mask(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        int visible = value.length() >= 6 ? 2 : 1;
        return value.substring(0, visible) + "•".repeat(Math.min(value.length() - visible, 12))
                + " (" + value.length() + " chars)";
    }
}
