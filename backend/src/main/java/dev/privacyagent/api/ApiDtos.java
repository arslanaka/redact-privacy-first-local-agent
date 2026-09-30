package dev.privacyagent.api;

import dev.privacyagent.analysis.PrivacySummary;
import dev.privacyagent.audit.AuditEvent;
import dev.privacyagent.detection.Detection;
import dev.privacyagent.detection.PiiType;
import dev.privacyagent.document.Document;
import dev.privacyagent.redaction.RedactionReport;
import dev.privacyagent.review.DetectedEntity;
import dev.privacyagent.review.ReviewService;
import dev.privacyagent.review.ReviewStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Request/response DTOs. Persistence entities never leave the service layer; storage keys are never exposed. */
public final class ApiDtos {

    private ApiDtos() {}

    public record ErrorInfo(String code, String message) {}

    public record ModelInfo(String name, boolean isMock) {}

    public record DocumentResponse(UUID id, String originalFilename, String contentType, String format, long size,
                                   String status, String currentStage, Instant createdAt, Instant updatedAt,
                                   Instant expiresAt, boolean hasRedactedOutput, ModelInfo model, ErrorInfo error,
                                   PrivacySummary summary, RedactionReport redaction, Integer textCharacters) {

        static DocumentResponse from(Document d, int retentionHours) {
            return new DocumentResponse(d.getId(), d.getOriginalFilename(), d.getContentType(), d.getFormat().name(),
                    d.getSize(), d.getStatus().name(), d.getCurrentStage() == null ? null : d.getCurrentStage().name(),
                    d.getCreatedAt(), d.getUpdatedAt(), d.getCreatedAt().plus(retentionHours, ChronoUnit.HOURS),
                    d.getRedactedStorageKey() != null,
                    d.getModelName() == null ? null : new ModelInfo(d.getModelName(), Boolean.TRUE.equals(d.getModelIsMock())),
                    d.getErrorCode() == null ? null : new ErrorInfo(d.getErrorCode().name(), d.getErrorMessage()),
                    d.getAnalysisSummary(), d.getRedactionReport(),
                    d.getExtractedText() == null ? null : d.getExtractedText().length());
        }
    }

    public record EntityResponse(UUID id, UUID documentId, String text, String type, double confidence,
                                 String confidenceLevel, int startOffset, int endOffset, String source,
                                 String reviewStatus, String recommendation, String recommendationReason,
                                 Double regexConfidence, Double modelConfidence, String modelLabels,
                                 String detectorNote, String contextBefore, String contextAfter, Instant createdAt,
                                 Instant reviewedAt) {

        static EntityResponse from(DetectedEntity e, String before, String after) {
            return new EntityResponse(e.getId(), e.getDocumentId(), e.getText(), e.getType().name(), e.getConfidence(),
                    e.confidenceLevel().name(), e.getStartOffset(), e.getEndOffset(), e.getSource().name(),
                    e.getReviewStatus().name(), e.getRecommendation().name(), e.getRecommendationReason(),
                    e.getRegexConfidence(), e.getModelConfidence(), e.getModelLabels(), e.getDetectorNote(), before,
                    after, e.getCreatedAt(), e.getReviewedAt());
        }

        static EntityResponse from(ReviewService.EntityView v) {
            return from(v.entity(), v.contextBefore(), v.contextAfter());
        }

        static EntityResponse from(DetectedEntity e) {
            return from(e, null, null);
        }
    }

    public record AuditEventResponse(UUID id, String eventType, String message, Map<String, Object> metadata,
                                     Instant createdAt, String actor) {
        static AuditEventResponse from(AuditEvent e) {
            return new AuditEventResponse(e.getId(), e.getEventType().name(), e.getMessage(), e.getMetadata(),
                    e.getCreatedAt(), e.getActor());
        }
    }

    @Schema(description = "Provide reviewStatus, type, or both")
    public record UpdateEntityRequest(ReviewStatus reviewStatus, PiiType type) {}

    @Schema(description = "Either startOffset+endOffset (from a text selection) or text (+ allOccurrences)")
    public record ManualRedactionRequest(@Min(0) Integer startOffset, @Min(1) Integer endOffset,
                                         @Size(max = 500) String text, @NotNull PiiType type,
                                         Boolean allOccurrences) {}

    public record BulkApproveRequest(@Schema(description = "Must be true: bulk approval requires explicit confirmation")
                                     boolean confirm) {}

    public record BulkApproveResponse(int approved, List<UUID> entityIds) {}

    public record EvaluationRequest(@NotNull @Size(max = 200_000) String text,
                                    @NotNull dev.privacyagent.evaluation.EvaluationService.Method method) {}

    public record EvaluationEntity(String text, String type, double confidence, int start, int end, String source) {
        static EvaluationEntity from(Detection d) {
            return new EvaluationEntity(d.text(), d.type().name(), d.confidence(), d.start(), d.end(), d.source().name());
        }
    }

    public record EvaluationResponse(String method, List<EvaluationEntity> entities, long durationMs, String model,
                                     Boolean modelIsMock) {}

    public record SystemStatus(String backend, String aiService, String model, boolean modelIsMock, String detail,
                               long maxUploadBytes, List<String> supportedExtensions, int retentionHours,
                               String redactionStyle, Capacity capacity, Cost cost) {}

    /**
     * What this deployment can process: measured model throughput and the hard limits, so the UI can estimate
     * durations and explain the ceiling (one model call per document, bounded by the AI read timeout).
     */
    public record Capacity(Double spansPerSecond, Integer cpuThreads, Integer maxTextChars, long aiTimeoutSeconds,
                           int analysisWorkers, int analysisQueueCapacity) {}

    public record Cost(String host, double vcpuUsdPerSecond, double memoryGbUsdPerSecond, double modelMemoryGb,
                       double otherServicesMemoryGb) {}
}
