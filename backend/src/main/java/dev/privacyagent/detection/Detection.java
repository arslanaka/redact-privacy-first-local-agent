package dev.privacyagent.detection;

import java.util.List;

/**
 * An immutable detection candidate produced by a detector or the merger, before persistence.
 * Offsets are Java string (UTF-16) indices into the extracted text, end exclusive.
 *
 * @param regexConfidence confidence contributed by the rule, if any
 * @param modelConfidence confidence contributed by the model, if any
 * @param modelLabels     the model's native labels, e.g. ["given name", "surname"]
 * @param note            short human-readable provenance, e.g. "IBAN checksum valid"
 * @param typeConflict    rule and model overlapped but disagreed on the type
 */
public record Detection(String text, PiiType type, double confidence, int start, int end, DetectionSource source,
                        Double regexConfidence, Double modelConfidence, List<String> modelLabels, String note,
                        boolean typeConflict) {

    public Detection {
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("invalid span [%d,%d)".formatted(start, end));
        }
        if (confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("confidence out of range: " + confidence);
        }
        modelLabels = modelLabels == null ? List.of() : List.copyOf(modelLabels);
    }

    public static Detection regex(String text, PiiType type, double confidence, int start, int end, String note) {
        return new Detection(text, type, confidence, start, end, DetectionSource.REGEX, confidence, null, List.of(), note, false);
    }

    public static Detection model(String text, PiiType type, double confidence, int start, int end, List<String> labels) {
        return new Detection(text, type, confidence, start, end, DetectionSource.LAYA, null, confidence, labels, null, false);
    }

    public int length() {
        return end - start;
    }

    public boolean overlaps(Detection other) {
        return start < other.end && other.start < end;
    }

    public int overlapLength(Detection other) {
        return Math.max(0, Math.min(end, other.end) - Math.max(start, other.start));
    }
}
