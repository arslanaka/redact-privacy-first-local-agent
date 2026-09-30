package dev.privacyagent.analysis;

import dev.privacyagent.detection.Detection;
import dev.privacyagent.detection.DetectionSource;
import dev.privacyagent.review.ConfidenceLevel;
import dev.privacyagent.review.Recommendation;
import org.springframework.stereotype.Component;

/** Per-entity suggestion shown to the reviewer. It never makes the decision. */
@Component
public class RecommendationPolicy {

    public record Advice(Recommendation recommendation, String reason) {}

    public Advice advise(Detection d) {
        if (d.typeConflict()) {
            return new Advice(Recommendation.REVIEW,
                    "Detectors disagree on the category. Check the category before approving.");
        }
        ConfidenceLevel level = ConfidenceLevel.of(d.confidence());
        if (d.type().isContextDependent()) {
            return new Advice(level == ConfidenceLevel.LOW ? Recommendation.LIKELY_KEEP : Recommendation.REVIEW,
                    "%s is often not personal data on its own. Redact only if it identifies someone."
                            .formatted(d.type().name().charAt(0) + d.type().name().substring(1).toLowerCase()));
        }
        if (d.source() == DetectionSource.HYBRID) {
            return new Advice(Recommendation.REDACT, "Confirmed by a deterministic rule and the local model.");
        }
        if (d.source() == DetectionSource.REGEX) {
            return new Advice(Recommendation.REDACT, "Matched a validated pattern (%s).".formatted(d.note()));
        }
        return switch (level) {
            case HIGH -> new Advice(Recommendation.REDACT, "Likely personal data according to the local model.");
            case MEDIUM -> new Advice(Recommendation.REVIEW, "The model is unsure. Check the surrounding context.");
            case LOW -> new Advice(Recommendation.LIKELY_KEEP,
                    "Low model confidence: possibly a false positive. Check the context before redacting.");
        };
    }
}
