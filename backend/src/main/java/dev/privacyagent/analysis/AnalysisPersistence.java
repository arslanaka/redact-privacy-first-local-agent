package dev.privacyagent.analysis;

import dev.privacyagent.audit.AuditEventType;
import dev.privacyagent.audit.AuditService;
import dev.privacyagent.common.ErrorCode;
import dev.privacyagent.common.PrivacyAgentException;
import dev.privacyagent.detection.Detection;
import dev.privacyagent.document.AnalysisStage;
import dev.privacyagent.document.Document;
import dev.privacyagent.document.DocumentRepository;
import dev.privacyagent.document.DocumentStatus;
import dev.privacyagent.review.DetectedEntity;
import dev.privacyagent.review.DetectedEntityRepository;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Short transactions used by the orchestrator. Each workflow step commits on its own so the
 * processing screen can follow progress while the (slow) model call is running.
 */
@Service
@RequiredArgsConstructor
class AnalysisPersistence {

    record Source(String storageKey, dev.privacyagent.document.DocumentFormat format) {}

    private final DocumentRepository documents;
    private final DetectedEntityRepository entities;
    private final AuditService audit;
    private final RecommendationPolicy recommendations;
    private final Clock clock;

    @Transactional
    public Source beginExtraction(UUID id) {
        Document doc = load(id);
        doc.enterStage(AnalysisStage.EXTRACTING_TEXT, clock.instant());
        return new Source(doc.getStorageKey(), doc.getFormat());
    }

    @Transactional
    public void saveExtractedText(UUID id, String text, long durationMs) {
        Document doc = load(id);
        doc.recordExtractedText(text, clock.instant());
        doc.transitionTo(DocumentStatus.ANALYSING, clock.instant());
        long lines = text.isEmpty() ? 0 : text.chars().filter(c -> c == '\n').count() + 1;
        audit.record(id, AuditEventType.TEXT_EXTRACTED,
                "Text extraction completed: %,d characters".formatted(text.length()),
                Map.of("characters", text.length(), "lines", lines, "durationMs", durationMs));
    }

    @Transactional
    public void enterStage(UUID id, AnalysisStage stage) {
        load(id).enterStage(stage, clock.instant());
    }

    @Transactional
    public void recordModel(UUID id, String modelName, boolean isMock) {
        load(id).recordModel(modelName, isMock);
    }

    @Transactional
    public void saveReviewQueue(UUID id, List<Detection> detections, PrivacySummary summary) {
        Document doc = load(id);
        entities.deleteByDocumentId(id); // a re-analysis replaces earlier candidates
        var now = clock.instant();
        List<DetectedEntity> queue = detections.stream().map(d -> {
            var advice = recommendations.advise(d);
            return DetectedEntity.candidate(id, d.text(), d.type(), d.confidence(), d.start(), d.end(), d.source(),
                    advice.recommendation(), advice.reason(), d.regexConfidence(), d.modelConfidence(),
                    d.modelLabels().isEmpty() ? null : String.join(", ", d.modelLabels()), d.note(), now);
        }).toList();
        entities.saveAll(queue);
        doc.completeAnalysis(summary, !queue.isEmpty(), now);
        audit.record(id, AuditEventType.PII_DETECTED,
                "%d potential PII items ready for review (risk %s)".formatted(queue.size(), summary.riskLevel()),
                Map.of("categories", summary.categories(), "riskLevel", summary.riskLevel().name(),
                        "highConfidence", summary.highConfidence(), "needsReview", summary.needsReview(),
                        "lowConfidence", summary.lowConfidence()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID id, ErrorCode code, String message, AnalysisStage stage) {
        documents.findById(id).ifPresent(doc -> {
            doc.fail(code, message, clock.instant());
            audit.record(id, AuditEventType.ERROR_OCCURRED, "Analysis failed during %s: %s".formatted(stage, message),
                    Map.of("errorCode", code.name(), "stage", String.valueOf(stage)));
        });
    }

    private Document load(UUID id) {
        return documents.findById(id).orElseThrow(PrivacyAgentException::documentNotFound);
    }
}
