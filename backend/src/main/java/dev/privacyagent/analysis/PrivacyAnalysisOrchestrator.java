package dev.privacyagent.analysis;

import dev.privacyagent.analysis.PrivacySummary.ModelSummary;
import dev.privacyagent.analysis.PrivacySummary.ToolCall;
import dev.privacyagent.audit.AuditEventType;
import dev.privacyagent.audit.AuditService;
import dev.privacyagent.common.ErrorCode;
import dev.privacyagent.common.PrivacyAgentException;
import dev.privacyagent.detection.Detection;
import dev.privacyagent.detection.DetectionMerger;
import dev.privacyagent.detection.DetectionSource;
import dev.privacyagent.detection.ModelPiiDetector;
import dev.privacyagent.detection.RegexPiiDetector;
import dev.privacyagent.document.AnalysisStage;
import dev.privacyagent.parsing.DocumentParserRegistry;
import dev.privacyagent.review.ConfidenceLevel;
import dev.privacyagent.storage.FileStorage;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Agent-style coordinator of the privacy analysis. It has a fixed set of tools and calls them in a
 * deterministic plan; there is no LLM choosing the next step, which keeps the workflow auditable
 * and reproducible:
 *
 * <pre>
 * extract_document -> detect_regex_pii -> detect_model_pii -> merge_detections
 *                  -> calculate_privacy_risk -> prepare_review_queue
 * </pre>
 *
 * Every tool call is timed and recorded in a tool trace (part of the {@link PrivacySummary}) and
 * the audit trail. Traces and audit messages carry counts and categories only, never document text.
 * If any tool fails, the document is marked FAILED with a structured error; a failed model call is
 * never papered over with a partial, regex-only result.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PrivacyAnalysisOrchestrator {

    private final AnalysisPersistence persistence;
    private final FileStorage storage;
    private final DocumentParserRegistry parsers;
    private final RegexPiiDetector regexDetector;
    private final ModelPiiDetector modelDetector;
    private final DetectionMerger merger;
    private final PrivacyRiskCalculator riskCalculator;
    private final AuditService audit;
    private final Clock clock;

    public void run(UUID documentId) {
        MDC.put("documentId", documentId.toString());
        var trace = new ArrayList<ToolCall>();
        var stage = new AnalysisStage[] {AnalysisStage.EXTRACTING_TEXT};
        long started = System.nanoTime();
        try {
            // Tool: extract_document()
            String text = tool(trace, "extract_document", documentId, stage, AnalysisStage.EXTRACTING_TEXT,
                    () -> extractDocument(documentId), t -> "%,d characters extracted".formatted(t.length()));

            // Tool: detect_regex_pii()
            List<Detection> regex = tool(trace, "detect_regex_pii", documentId, stage, AnalysisStage.REGEX_DETECTION,
                    () -> regexDetector.detect(text), r -> "%d entities found".formatted(r.size()));
            audit.record(documentId, AuditEventType.REGEX_ANALYSIS_COMPLETED,
                    "Regex analysis completed: %d entities found".formatted(regex.size()),
                    Map.of("count", regex.size(), "categories", countBy(regex), "durationMs", last(trace).durationMs()));

            // Tool: detect_model_pii()
            ModelPiiDetector.Result model = tool(trace, "detect_model_pii", documentId, stage,
                    AnalysisStage.MODEL_DETECTION, () -> modelDetector.detect(text),
                    m -> "%d entities found by %s%s (%d candidate spans scored)".formatted(m.detections().size(),
                            m.modelName(), m.isMock() ? " [MOCK]" : "", m.candidatesScored()));
            persistence.recordModel(documentId, model.modelName(), model.isMock());
            audit.record(documentId, AuditEventType.MODEL_ANALYSIS_COMPLETED,
                    "Local model analysis completed: %d entities found (%s%s)".formatted(model.detections().size(),
                            model.modelName(), model.isMock() ? ", MOCK MODEL" : ""),
                    Map.of("count", model.detections().size(), "model", model.modelName(), "isMock", model.isMock(),
                            "candidatesScored", model.candidatesScored(), "inferenceMs", model.modelTimeMs(),
                            "rejectedOutputs", model.rejected(), "categories", countBy(model.detections())));

            // Tool: merge_detections()
            List<Detection> merged = tool(trace, "merge_detections", documentId, stage, AnalysisStage.MERGING,
                    () -> merger.merge(regex, model.detections()),
                    m -> "%d unique candidates (%d hybrid)".formatted(m.size(), countSource(m, DetectionSource.HYBRID)));
            long conflicts = merged.stream().filter(Detection::typeConflict).count();
            audit.record(documentId, AuditEventType.DETECTIONS_MERGED,
                    "Detection merge completed: %d unique candidates".formatted(merged.size()),
                    Map.of("unique", merged.size(), "hybrid", countSource(merged, DetectionSource.HYBRID),
                            "typeConflicts", conflicts, "inputs", regex.size() + model.detections().size()));

            // Tool: calculate_privacy_risk()
            PrivacyRiskCalculator.Assessment risk = tool(trace, "calculate_privacy_risk", documentId, stage,
                    AnalysisStage.PREPARING_REVIEW, () -> riskCalculator.assess(merged),
                    a -> "risk %s".formatted(a.level()));

            // Tool: prepare_review_queue()
            tool(trace, "prepare_review_queue", documentId, stage, AnalysisStage.PREPARING_REVIEW, () -> {
                // The stored trace ends at calculate_privacy_risk: this step persists it, so it cannot include itself.
                persistence.saveReviewQueue(documentId, merged, summarize(merged, risk, model, trace));
                return merged.size();
            }, n -> "%d candidates queued for human review".formatted(n));

            log.info("analysis completed documentId={} candidates={} risk={} durationMs={}", documentId,
                    merged.size(), risk.level(), (System.nanoTime() - started) / 1_000_000);
        } catch (PrivacyAgentException e) {
            log.warn("analysis failed documentId={} stage={} code={}", documentId, stage[0], e.code());
            persistence.markFailed(documentId, e.code(), e.getMessage(), stage[0]);
        } catch (RuntimeException e) {
            log.error("analysis failed unexpectedly documentId={} stage={}", documentId, stage[0], e);
            persistence.markFailed(documentId, ErrorCode.ANALYSIS_FAILED, "Analysis failed unexpectedly", stage[0]);
        } finally {
            MDC.remove("documentId");
            MDC.remove("stage");
        }
    }

    private String extractDocument(UUID documentId) {
        var source = persistence.beginExtraction(documentId);
        long t0 = System.nanoTime();
        String text = parsers.forFormat(source.format()).extractText(storage.read(source.storageKey()));
        persistence.saveExtractedText(documentId, text, (System.nanoTime() - t0) / 1_000_000);
        return text;
    }

    private <T> T tool(List<ToolCall> trace, String name, UUID documentId, AnalysisStage[] stageHolder,
                       AnalysisStage stage, Supplier<T> action, Function<T, String> describe) {
        stageHolder[0] = stage;
        MDC.put("stage", stage.name());
        persistence.enterStage(documentId, stage);
        long t0 = System.nanoTime();
        try {
            T result = action.get();
            long ms = (System.nanoTime() - t0) / 1_000_000;
            trace.add(new ToolCall(name, "OK", ms, describe.apply(result)));
            log.info("tool completed tool={} durationMs={}", name, ms);
            return result;
        } catch (RuntimeException e) {
            trace.add(new ToolCall(name, "FAILED", (System.nanoTime() - t0) / 1_000_000, e.getClass().getSimpleName()));
            throw e;
        }
    }

    PrivacySummary summarize(List<Detection> merged, PrivacyRiskCalculator.Assessment risk,
                             ModelPiiDetector.Result model, List<ToolCall> trace) {
        int high = 0, medium = 0, low = 0;
        for (Detection d : merged) {
            switch (ConfidenceLevel.of(d.confidence())) {
                case HIGH -> high++;
                case MEDIUM -> medium++;
                case LOW -> low++;
            }
        }
        Map<String, Integer> sources = merged.stream().collect(Collectors.groupingBy(d -> d.source().name(),
                TreeMap::new, Collectors.summingInt(d -> 1)));
        return new PrivacySummary(merged.size(), high, medium, low, countBy(merged), sources, risk.level(),
                risk.reasons(), riskCalculator.recommendation(risk.level(), medium, low),
                new ModelSummary(model.modelName(), model.isMock(), model.candidatesScored(), model.modelTimeMs(),
                        model.rejected()),
                List.copyOf(trace), clock.instant());
    }

    static Map<String, Integer> countBy(List<Detection> detections) {
        return detections.stream().collect(Collectors.groupingBy(d -> d.type().name(), TreeMap::new,
                Collectors.summingInt(d -> 1)));
    }

    private static long countSource(List<Detection> detections, DetectionSource source) {
        return detections.stream().filter(d -> d.source() == source).count();
    }

    private static ToolCall last(List<ToolCall> trace) {
        return trace.getLast();
    }
}
