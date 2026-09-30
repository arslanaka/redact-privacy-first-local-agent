package dev.privacyagent.document;

import dev.privacyagent.audit.AuditEventRepository;
import dev.privacyagent.audit.AuditEventType;
import dev.privacyagent.audit.AuditService;
import dev.privacyagent.common.PrivacyAgentException;
import dev.privacyagent.review.DetectedEntityRepository;
import dev.privacyagent.storage.FileStorage;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

    private final DocumentRepository documents;
    private final DetectedEntityRepository entities;
    private final AuditEventRepository auditEvents;
    private final AuditService audit;
    private final UploadValidator validator;
    private final FileStorage storage;
    private final Clock clock;

    @Transactional
    public Document upload(MultipartFile file) {
        var upload = validator.validate(file);
        String key = storage.store(upload.content(), upload.format().outputExtension());
        registerRollbackCleanup(key);
        var doc = Document.uploaded(UUID.randomUUID(), upload.filename(), upload.contentType(), upload.format(),
                upload.content().length, key, clock.instant());
        documents.save(doc);
        audit.record(doc.getId(), AuditEventType.DOCUMENT_UPLOADED,
                "Document uploaded (%s, %s)".formatted(upload.format(), humanSize(doc.getSize())),
                Map.of("format", upload.format().name(), "sizeBytes", doc.getSize()));
        log.info("document uploaded documentId={} format={} sizeBytes={}", doc.getId(), doc.getFormat(), doc.getSize());
        return doc;
    }

    @Transactional(readOnly = true)
    public Document get(UUID id) {
        return documents.findById(id).orElseThrow(PrivacyAgentException::documentNotFound);
    }

    /**
     * Removes original file, output file, extracted text, detections and the document's audit trail,
     * then records a single DOCUMENT_DELETED (or _EXPIRED) event with counts only.
     */
    @Transactional
    public Map<String, Object> delete(UUID id, AuditEventType reason) {
        Document doc = get(id);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("entitiesDeleted", entities.deleteByDocumentId(id));
        summary.put("auditEventsDeleted", auditEvents.deleteByDocumentId(id));
        int files = 0;
        files += deleteQuietly(doc.getStorageKey()) ? 1 : 0;
        files += doc.getRedactedStorageKey() != null && deleteQuietly(doc.getRedactedStorageKey()) ? 1 : 0;
        summary.put("filesDeleted", files);
        documents.delete(doc);
        audit.record(id, reason, reason == AuditEventType.DOCUMENT_EXPIRED
                ? "Document removed by retention policy" : "Document and all derived data deleted", summary);
        log.info("document deleted documentId={} reason={} files={}", id, reason, files);
        return summary;
    }

    private boolean deleteQuietly(String key) {
        try {
            return storage.delete(key);
        } catch (RuntimeException e) {
            log.warn("could not delete stored file for document: {}", e.getMessage());
            return false;
        }
    }

    private void registerRollbackCleanup(String key) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_ROLLED_BACK) {
                        deleteQuietly(key);
                    }
                }
            });
        }
    }

    static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        return bytes < 1024 * 1024 ? "%.1f KB".formatted(bytes / 1024.0) : "%.1f MB".formatted(bytes / (1024.0 * 1024));
    }
}
