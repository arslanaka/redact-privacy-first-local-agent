package dev.privacyagent.analysis;

import dev.privacyagent.detection.Detection;
import dev.privacyagent.detection.PiiType;
import dev.privacyagent.review.ConfidenceLevel;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Deterministic document risk rules (no LLM). Evaluated top-down; the first matching level wins.
 *
 * <p><b>HIGH</b> if any of:
 * <ul>
 *   <li>a sensitive identifier (IBAN, CREDIT_CARD, ID_NUMBER, DATE_OF_BIRTH) detected with confidence >= 0.70</li>
 *   <li>{@value #HIGH_VOLUME} or more detections</li>
 * </ul>
 * <p><b>MEDIUM</b> if any of:
 * <ul>
 *   <li>a PERSON together with a direct contact channel (EMAIL, PHONE or ADDRESS): the combination identifies someone</li>
 *   <li>{@value #MEDIUM_VOLUME} or more detections</li>
 *   <li>a sensitive identifier detected only with low confidence</li>
 * </ul>
 * <p><b>LOW</b> otherwise, including documents with no detections.
 */
@Component
public class PrivacyRiskCalculator {

    static final int HIGH_VOLUME = 15;
    static final int MEDIUM_VOLUME = 5;
    private static final Set<PiiType> CONTACT = EnumSet.of(PiiType.EMAIL, PiiType.PHONE, PiiType.ADDRESS);

    public record Assessment(RiskLevel level, List<String> reasons) {}

    public Assessment assess(List<Detection> detections) {
        List<String> reasons = new ArrayList<>();
        Set<PiiType> confidentSensitive = detections.stream()
                .filter(d -> d.type().isSensitiveIdentifier() && d.confidence() >= ConfidenceLevel.MEDIUM_THRESHOLD)
                .map(Detection::type)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(PiiType.class)));
        if (!confidentSensitive.isEmpty()) {
            reasons.add("Sensitive identifiers found: " + confidentSensitive);
        }
        if (detections.size() >= HIGH_VOLUME) {
            reasons.add("High volume of personal data (%d detections)".formatted(detections.size()));
        }
        if (!reasons.isEmpty()) {
            return new Assessment(RiskLevel.HIGH, reasons);
        }

        Set<PiiType> types = detections.stream().map(Detection::type)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(PiiType.class)));
        if (types.contains(PiiType.PERSON) && types.stream().anyMatch(CONTACT::contains)) {
            reasons.add("A person's name appears together with contact details");
        }
        if (detections.size() >= MEDIUM_VOLUME) {
            reasons.add("Several personal data items (%d detections)".formatted(detections.size()));
        }
        if (types.stream().anyMatch(PiiType::isSensitiveIdentifier)) {
            reasons.add("Possible sensitive identifier with low confidence");
        }
        if (!reasons.isEmpty()) {
            return new Assessment(RiskLevel.MEDIUM, reasons);
        }
        return new Assessment(RiskLevel.LOW, detections.isEmpty()
                ? List.of("No personal data detected (detectors can miss things; skim the document)")
                : List.of("Only a few low-sensitivity items detected"));
    }

    public String recommendation(RiskLevel level, int needsReview, int lowConfidence) {
        String base = switch (level) {
            case HIGH -> "Redact before sharing: the document contains high-risk personal data.";
            case MEDIUM -> "Redact identifying details before sharing outside the team.";
            case LOW -> "Low risk, but confirm nothing was missed before sharing.";
        };
        return needsReview + lowConfidence > 0 ? "Review all ambiguous detections before export. " + base : base;
    }
}
