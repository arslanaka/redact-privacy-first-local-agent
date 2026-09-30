package dev.privacyagent.detection;

public enum DetectionSource {
    /** Local Laya model via the AI service (or the clearly flagged mock). */
    LAYA,
    /** Deterministic rule in {@link RegexPiiDetector}. */
    REGEX,
    /** Both a rule and the model found overlapping spans; merged by {@link DetectionMerger}. */
    HYBRID,
    /** Added by a human reviewer. */
    MANUAL
}
