package dev.privacyagent.document;

import dev.privacyagent.analysis.PrivacySummary;
import dev.privacyagent.common.ErrorCode;
import dev.privacyagent.common.PrivacyAgentException;
import dev.privacyagent.redaction.RedactionReport;
import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The document aggregate. Owns its lifecycle: status changes only go through {@link #transitionTo},
 * which enforces the transitions declared in {@link DocumentStatus}.
 */
@Entity
@Table(name = "documents")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Document {

    @Id
    private UUID id;

    @Column(name = "original_filename", nullable = false)
    private String originalFilename;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DocumentFormat format;

    @Column(name = "size_bytes", nullable = false)
    private long size;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DocumentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_stage")
    private AnalysisStage currentStage;

    @Column(name = "storage_key", nullable = false)
    private String storageKey;

    /** Storage key of the redacted output (the "redactedFilePath"); internal, never exposed. */
    @Column(name = "redacted_storage_key")
    private String redactedStorageKey;

    @Basic(fetch = FetchType.LAZY)
    @Column(name = "extracted_text", columnDefinition = "text")
    private String extractedText;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "analysis_summary", columnDefinition = "jsonb")
    private PrivacySummary analysisSummary;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "redaction_report", columnDefinition = "jsonb")
    private RedactionReport redactionReport;

    @Column(name = "model_name")
    private String modelName;

    @Column(name = "model_is_mock")
    private Boolean modelIsMock;

    @Enumerated(EnumType.STRING)
    @Column(name = "error_code")
    private ErrorCode errorCode;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    public static Document uploaded(UUID id, String originalFilename, String contentType, DocumentFormat format,
                                    long size, String storageKey, Instant now) {
        var doc = new Document();
        doc.id = id;
        doc.originalFilename = originalFilename;
        doc.contentType = contentType;
        doc.format = format;
        doc.size = size;
        doc.storageKey = storageKey;
        doc.status = DocumentStatus.UPLOADED;
        doc.createdAt = now;
        doc.updatedAt = now;
        return doc;
    }

    public void transitionTo(DocumentStatus target, Instant now) {
        if (!status.canTransitionTo(target)) {
            throw PrivacyAgentException.invalidState(
                    "Cannot move document from %s to %s".formatted(status, target));
        }
        status = target;
        updatedAt = now;
    }

    public void startAnalysis(Instant now) {
        if (!status.canStartAnalysis()) {
            throw PrivacyAgentException.invalidState("Analysis can only start for UPLOADED or FAILED documents (current: %s)"
                    .formatted(status));
        }
        transitionTo(DocumentStatus.EXTRACTING, now);
        currentStage = AnalysisStage.QUEUED;
        errorCode = null;
        errorMessage = null;
    }

    public void recordExtractedText(String text, Instant now) {
        extractedText = text;
        updatedAt = now;
    }

    public void enterStage(AnalysisStage stage, Instant now) {
        currentStage = stage;
        updatedAt = now;
    }

    public void recordModel(String name, boolean isMock) {
        modelName = name;
        modelIsMock = isMock;
    }

    public void completeAnalysis(PrivacySummary summary, boolean reviewNeeded, Instant now) {
        analysisSummary = summary;
        currentStage = AnalysisStage.DONE;
        transitionTo(reviewNeeded ? DocumentStatus.REVIEW_REQUIRED : DocumentStatus.APPROVED, now);
    }

    public void fail(ErrorCode code, String message, Instant now) {
        status = DocumentStatus.FAILED; // allowed from any in-flight state; recorded for the user
        errorCode = code;
        errorMessage = message == null ? null : message.substring(0, Math.min(message.length(), 500));
        updatedAt = now;
    }

    /** Re-evaluates REVIEW_REQUIRED/APPROVED after a review decision; a changed decision invalidates any export. */
    public boolean syncReviewState(long pendingEntities, Instant now) {
        boolean outputInvalidated = false;
        if (status == DocumentStatus.COMPLETED) {
            redactedStorageKey = null;
            redactionReport = null;
            outputInvalidated = true;
        }
        DocumentStatus target = pendingEntities > 0 ? DocumentStatus.REVIEW_REQUIRED : DocumentStatus.APPROVED;
        if (status != target) {
            transitionTo(target, now);
        } else {
            updatedAt = now;
        }
        return outputInvalidated;
    }

    public void startRedaction(Instant now) {
        transitionTo(DocumentStatus.REDACTING, now);
    }

    public void completeRedaction(String outputKey, RedactionReport report, Instant now) {
        redactedStorageKey = outputKey;
        redactionReport = report;
        transitionTo(DocumentStatus.COMPLETED, now);
    }

    public void updateSummary(PrivacySummary summary) {
        analysisSummary = summary;
    }
}
