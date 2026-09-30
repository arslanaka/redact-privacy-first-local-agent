package dev.privacyagent.review;

/**
 * Confidence buckets shown in the UI. Confidence is a model/rule signal, not factual certainty.
 * HIGH >= 0.90, MEDIUM 0.70-0.89, LOW < 0.70.
 */
public enum ConfidenceLevel {
    HIGH,
    MEDIUM,
    LOW;

    public static final double HIGH_THRESHOLD = 0.90;
    public static final double MEDIUM_THRESHOLD = 0.70;

    public static ConfidenceLevel of(double confidence) {
        if (confidence >= HIGH_THRESHOLD) {
            return HIGH;
        }
        return confidence >= MEDIUM_THRESHOLD ? MEDIUM : LOW;
    }
}
