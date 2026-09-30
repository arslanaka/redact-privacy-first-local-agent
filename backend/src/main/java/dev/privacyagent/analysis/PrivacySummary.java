package dev.privacyagent.analysis;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Output of the orchestrator: a snapshot taken when analysis finished. Review decisions made later
 * are reported separately (see AnalysisView), so this stays a faithful record of what the detectors said.
 *
 * @param needsReview detections in the MEDIUM confidence band (0.70-0.89)
 */
public record PrivacySummary(
        int totalDetections,
        int highConfidence,
        int needsReview,
        int lowConfidence,
        Map<String, Integer> categories,
        Map<String, Integer> sources,
        RiskLevel riskLevel,
        List<String> riskReasons,
        String recommendation,
        ModelSummary model,
        List<ToolCall> toolCalls,
        Instant generatedAt) {

    /** Which model ran, so a mock-model analysis can never be mistaken for a real one. */
    public record ModelSummary(String name, boolean isMock, int candidatesScored, long inferenceMs, int rejectedOutputs) {}

    /** One step of the orchestrator's tool trace, shown in the UI and kept for auditability. */
    public record ToolCall(String tool, String status, long durationMs, String summary) {}
}
