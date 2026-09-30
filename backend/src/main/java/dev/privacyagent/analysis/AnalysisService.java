package dev.privacyagent.analysis;

import dev.privacyagent.audit.AuditEventType;
import dev.privacyagent.audit.AuditService;
import dev.privacyagent.common.ErrorCode;
import dev.privacyagent.common.PrivacyAgentException;
import dev.privacyagent.document.Document;
import dev.privacyagent.document.DocumentRepository;
import dev.privacyagent.document.DocumentStatus;
import dev.privacyagent.review.DetectedEntityRepository;
import dev.privacyagent.review.ReviewStatus;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@Service
public class AnalysisService {

    /** Snapshot summary plus live review progress. */
    public record AnalysisView(UUID documentId, DocumentStatus status, String stage, PrivacySummary summary,
                               ReviewProgress review, ErrorView error) {}

    public record ReviewProgress(long total, long pending, long approved, long rejected, long reviewed) {}

    public record ErrorView(String code, String message) {}

    private final DocumentRepository documents;
    private final DetectedEntityRepository entities;
    private final PrivacyAnalysisOrchestrator orchestrator;
    private final AuditService audit;
    private final Executor executor;
    private final Clock clock;

    public AnalysisService(DocumentRepository documents, DetectedEntityRepository entities,
                           PrivacyAnalysisOrchestrator orchestrator, AuditService audit,
                           @Qualifier("analysisExecutor") Executor executor, Clock clock) {
        this.documents = documents;
        this.entities = entities;
        this.orchestrator = orchestrator;
        this.audit = audit;
        this.executor = executor;
        this.clock = clock;
    }

    /** Validates the state, records the start, and runs the orchestrator asynchronously after commit. */
    @Transactional
    public Document start(UUID id) {
        Document doc = documents.findById(id).orElseThrow(PrivacyAgentException::documentNotFound);
        boolean retry = doc.getStatus() == DocumentStatus.FAILED;
        doc.startAnalysis(clock.instant());
        audit.record(id, AuditEventType.ANALYSIS_STARTED, retry ? "Analysis restarted after failure" : "Analysis started",
                Map.of("retry", retry));
        Runnable job = () -> orchestrator.run(id);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    executor.execute(job);
                }
            });
        } else {
            executor.execute(job);
        }
        return doc;
    }

    @Transactional(readOnly = true)
    public AnalysisView view(UUID id) {
        Document doc = documents.findById(id).orElseThrow(PrivacyAgentException::documentNotFound);
        return new AnalysisView(id, doc.getStatus(), doc.getCurrentStage() == null ? null : doc.getCurrentStage().name(),
                doc.getAnalysisSummary(), progress(id),
                doc.getErrorCode() == null ? null : new ErrorView(doc.getErrorCode().name(), doc.getErrorMessage()));
    }

    public ReviewProgress progress(UUID id) {
        long pending = entities.countByDocumentIdAndReviewStatus(id, ReviewStatus.PENDING);
        long approved = entities.countByDocumentIdAndReviewStatus(id, ReviewStatus.APPROVED);
        long rejected = entities.countByDocumentIdAndReviewStatus(id, ReviewStatus.REJECTED);
        return new ReviewProgress(pending + approved + rejected, pending, approved, rejected, approved + rejected);
    }

    /** An analysis thread does not survive a restart; mark such documents FAILED so they can be retried. */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void recoverInterruptedAnalyses() {
        documents.findAll().stream()
                .filter(d -> d.getStatus() == DocumentStatus.EXTRACTING || d.getStatus() == DocumentStatus.ANALYSING)
                .forEach(d -> {
                    d.fail(ErrorCode.ANALYSIS_FAILED, "Processing was interrupted by a server restart. Please retry.",
                            clock.instant());
                    audit.record(d.getId(), AuditEventType.ERROR_OCCURRED, "Processing interrupted by server restart",
                            Map.of("errorCode", ErrorCode.ANALYSIS_FAILED.name()));
                    log.warn("marked interrupted document as failed documentId={}", d.getId());
                });
    }
}
