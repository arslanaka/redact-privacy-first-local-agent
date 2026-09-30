package dev.privacyagent.redaction;

import dev.privacyagent.audit.AuditEventType;
import dev.privacyagent.audit.AuditService;
import dev.privacyagent.common.ErrorCode;
import dev.privacyagent.common.PrivacyAgentException;
import dev.privacyagent.config.PrivacyProperties;
import dev.privacyagent.config.PrivacyProperties.RedactionStyle;
import dev.privacyagent.document.Document;
import dev.privacyagent.document.DocumentFormat;
import dev.privacyagent.document.DocumentRepository;
import dev.privacyagent.document.DocumentStatus;
import dev.privacyagent.review.DetectedEntity;
import dev.privacyagent.review.DetectedEntityRepository;
import dev.privacyagent.review.ReviewStatus;
import dev.privacyagent.storage.FileStorage;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@Service
@RequiredArgsConstructor
public class RedactionService {

    public record Preview(String originalText, String redactedText, List<PreviewSpan> approvedSpans,
                          List<int[]> redactedRegions, boolean outputGenerated, String style, RedactionReport report) {}

    public record PreviewSpan(String entityId, String type, int start, int end) {}

    public record Download(String filename, String contentType, byte[] content) {}

    private static final List<String> PDF_LIMITATIONS = List.of(
            "The redacted PDF is re-rendered from extracted text: original layout, fonts, images and tables are not preserved.",
            "Text inside images or scanned pages is not detected or redacted (no OCR).");
    private static final List<String> TEXT_LIMITATIONS = List.of(
            "BLOCK style preserves the length of each redacted value; use LABEL style to hide lengths.");

    private final DocumentRepository documents;
    private final DetectedEntityRepository entities;
    private final TextRedactor textRedactor;
    private final PdfRedactedDocumentWriter pdfWriter;
    private final RedactionVerifier verifier;
    private final FileStorage storage;
    private final AuditService audit;
    private final PrivacyProperties properties;
    private final Clock clock;

    /**
     * Generates, verifies and stores the redacted file in one transaction. On any failure nothing is
     * committed: the document stays APPROVED with every review decision intact and can be retried.
     */
    @Transactional
    public Document generate(UUID documentId) {
        Document doc = load(documentId);
        if (doc.getStatus() != DocumentStatus.APPROVED && doc.getStatus() != DocumentStatus.COMPLETED) {
            long pending = entities.countByDocumentIdAndReviewStatus(documentId, ReviewStatus.PENDING);
            throw PrivacyAgentException.invalidState(pending > 0
                    ? "%d detected item(s) still need a review decision".formatted(pending)
                    : "Document is not ready for redaction (current: %s)".formatted(doc.getStatus()));
        }
        List<DetectedEntity> approved = entities.findByDocumentIdAndReviewStatusOrderByStartOffsetAsc(documentId,
                ReviewStatus.APPROVED);
        RedactionStyle style = properties.redaction().style();
        String previousOutput = doc.getRedactedStorageKey();
        audit.record(documentId, AuditEventType.REDACTION_STARTED,
                "Redaction started: %d approved items, %s style".formatted(approved.size(), style),
                Map.of("approved", approved.size(), "style", style.name()));
        doc.startRedaction(clock.instant());
        String newKey = null;
        try {
            TextRedactor.Result redacted = textRedactor.redact(doc.getExtractedText(), toSpans(approved), style);
            byte[] output = doc.getFormat() == DocumentFormat.PDF
                    ? pdfWriter.write(redacted.text(), redacted.redactedRegions(), style == RedactionStyle.LABEL)
                    : redacted.text().getBytes(StandardCharsets.UTF_8);
            var verification = verifier.verify(doc.getFormat(), output, redacted.text(), approved);
            if (!verification.passed()) {
                throw new PrivacyAgentException(ErrorCode.REDACTION_FAILED,
                        "Redaction verification failed; no file was produced",
                        Map.of("failures", verification.failures()), null);
            }
            newKey = storage.store(output, doc.getFormat().outputExtension());
            String keyToCleanOnRollback = newKey;
            afterTransaction(previousOutput, keyToCleanOnRollback);
            List<String> limitations = new ArrayList<>(doc.getFormat() == DocumentFormat.PDF ? PDF_LIMITATIONS : List.of());
            if (style == RedactionStyle.BLOCK) {
                limitations.addAll(TEXT_LIMITATIONS);
            }
            var report = new RedactionReport(clock.instant(), style.name(), doc.getFormat().name(), approved.size(),
                    redacted.redactedRegions().size(), redacted.redactedCharacters(), output.length, true,
                    verification.checks(), verification.residuals(), limitations);
            doc.completeRedaction(newKey, report, clock.instant());
            audit.record(documentId, AuditEventType.REDACTION_COMPLETED,
                    "Redacted document generated: %d regions redacted, verification passed".formatted(
                            redacted.redactedRegions().size()),
                    Map.of("regions", redacted.redactedRegions().size(), "outputBytes", output.length,
                            "format", doc.getFormat().name(), "verificationPassed", true));
            if (!verification.residuals().isEmpty()) {
                audit.record(documentId, AuditEventType.REDACTION_WARNING,
                        "%d approved value(s) also appear elsewhere un-redacted".formatted(verification.residuals().size()),
                        Map.of("residuals", verification.residuals().size()));
            }
            log.info("redaction completed documentId={} regions={} bytes={}", documentId,
                    redacted.redactedRegions().size(), output.length);
            return doc;
        } catch (PrivacyAgentException e) {
            recordFailure(documentId, e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            log.error("redaction failed documentId={}", documentId, e);
            recordFailure(documentId, "Unexpected error while generating the redacted file");
            throw new PrivacyAgentException(ErrorCode.REDACTION_FAILED, "Redaction failed; no file was produced", e);
        }
    }

    @Transactional(readOnly = true)
    public Preview preview(UUID documentId) {
        Document doc = load(documentId);
        if (doc.getExtractedText() == null) {
            throw PrivacyAgentException.invalidState("Document has not been analysed yet");
        }
        List<DetectedEntity> approved = entities.findByDocumentIdAndReviewStatusOrderByStartOffsetAsc(documentId,
                ReviewStatus.APPROVED);
        RedactionStyle style = properties.redaction().style();
        var redacted = textRedactor.redact(doc.getExtractedText(), toSpans(approved), style);
        var spans = approved.stream().map(e -> new PreviewSpan(e.getId().toString(), e.getType().name(),
                e.getStartOffset(), e.getEndOffset())).toList();
        return new Preview(doc.getExtractedText(), redacted.text(), spans, redacted.redactedRegions(),
                doc.getStatus() == DocumentStatus.COMPLETED, style.name(), doc.getRedactionReport());
    }

    @Transactional
    public Download download(UUID documentId) {
        Document doc = load(documentId);
        if (doc.getStatus() != DocumentStatus.COMPLETED || doc.getRedactedStorageKey() == null) {
            throw new PrivacyAgentException(ErrorCode.OUTPUT_NOT_AVAILABLE,
                    "No redacted file available. Generate the redacted document first.");
        }
        byte[] content = storage.read(doc.getRedactedStorageKey());
        String base = doc.getOriginalFilename().replaceFirst("\\.[^.]+$", "");
        String filename = "redacted-" + base + "." + doc.getFormat().outputExtension();
        audit.record(documentId, AuditEventType.EXPORT_CREATED, "Redacted file downloaded",
                Map.of("bytes", content.length, "format", doc.getFormat().name()));
        return new Download(filename, doc.getFormat().outputContentType(), content);
    }

    private void recordFailure(UUID documentId, String message) {
        audit.recordIndependently(documentId, AuditEventType.ERROR_OCCURRED, "Redaction failed: " + message,
                Map.of("errorCode", ErrorCode.REDACTION_FAILED.name()));
    }

    /** Delete the superseded file only after commit; delete the new file if the transaction rolls back. */
    private void afterTransaction(String previousKey, String newKey) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                String obsolete = status == STATUS_COMMITTED ? previousKey : newKey;
                if (obsolete != null) {
                    try {
                        storage.delete(obsolete);
                    } catch (RuntimeException e) {
                        log.warn("could not delete obsolete redaction output: {}", e.getMessage());
                    }
                }
            }
        });
    }

    private static List<RedactionSpan> toSpans(List<DetectedEntity> approved) {
        return approved.stream().map(e -> new RedactionSpan(e.getStartOffset(), e.getEndOffset(), e.getType())).toList();
    }

    private Document load(UUID id) {
        return documents.findById(id).orElseThrow(PrivacyAgentException::documentNotFound);
    }
}
