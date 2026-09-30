package dev.privacyagent.retention;

import dev.privacyagent.audit.AuditEventType;
import dev.privacyagent.config.PrivacyProperties;
import dev.privacyagent.document.DocumentRepository;
import dev.privacyagent.document.DocumentService;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Deletes documents (files, text, detections, audit trail) older than PRIVACY_DOCUMENT_RETENTION_HOURS. */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetentionService {

    private final DocumentRepository documents;
    private final DocumentService documentService;
    private final PrivacyProperties properties;
    private final Clock clock;

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "${privacy.retention.cleanup-interval}")
    public void scheduledCleanup() {
        purgeExpired();
    }

    public int purgeExpired() {
        var cutoff = clock.instant().minus(Duration.ofHours(properties.retention().documentHours()));
        List<UUID> expired = documents.findIdsCreatedBefore(cutoff);
        int deleted = 0;
        for (UUID id : expired) {
            try {
                documentService.delete(id, AuditEventType.DOCUMENT_EXPIRED);
                deleted++;
            } catch (RuntimeException e) {
                log.warn("retention cleanup failed documentId={} error={}", id, e.getMessage());
            }
        }
        if (deleted > 0) {
            log.info("retention cleanup deleted={} retentionHours={}", deleted, properties.retention().documentHours());
        }
        return deleted;
    }
}
