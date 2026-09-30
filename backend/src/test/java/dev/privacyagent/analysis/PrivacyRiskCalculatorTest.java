package dev.privacyagent.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import dev.privacyagent.detection.Detection;
import dev.privacyagent.detection.PiiType;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PrivacyRiskCalculatorTest {

    private final PrivacyRiskCalculator calc = new PrivacyRiskCalculator();
    private int cursor;

    private Detection d(PiiType type, double confidence) {
        cursor += 10;
        return Detection.model("x", type, confidence, cursor, cursor + 1, List.of());
    }

    @Test
    void noDetectionsIsLow() {
        assertThat(calc.assess(List.of()).level()).isEqualTo(RiskLevel.LOW);
    }

    @Test
    void sensitiveIdentifierIsHigh() {
        assertThat(calc.assess(List.of(d(PiiType.IBAN, 0.99))).level()).isEqualTo(RiskLevel.HIGH);
        assertThat(calc.assess(List.of(d(PiiType.DATE_OF_BIRTH, 0.95))).level()).isEqualTo(RiskLevel.HIGH);
    }

    @Test
    void lowConfidenceSensitiveIdentifierIsOnlyMedium() {
        assertThat(calc.assess(List.of(d(PiiType.ID_NUMBER, 0.55))).level()).isEqualTo(RiskLevel.MEDIUM);
    }

    @Test
    void personWithContactDetailsIsMedium() {
        var a = calc.assess(List.of(d(PiiType.PERSON, 0.9), d(PiiType.EMAIL, 0.97)));
        assertThat(a.level()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(a.reasons()).anyMatch(r -> r.contains("contact"));
    }

    @Test
    void personAloneIsLow() {
        assertThat(calc.assess(List.of(d(PiiType.PERSON, 0.9), d(PiiType.LOCATION, 0.9))).level()).isEqualTo(RiskLevel.LOW);
    }

    @Test
    void volumeThresholds() {
        List<Detection> five = new ArrayList<>();
        for (int i = 0; i < PrivacyRiskCalculator.MEDIUM_VOLUME; i++) {
            five.add(d(PiiType.LOCATION, 0.9));
        }
        assertThat(calc.assess(five).level()).isEqualTo(RiskLevel.MEDIUM);
        List<Detection> many = new ArrayList<>();
        for (int i = 0; i < PrivacyRiskCalculator.HIGH_VOLUME; i++) {
            many.add(d(PiiType.PERSON, 0.9));
        }
        assertThat(calc.assess(many).level()).isEqualTo(RiskLevel.HIGH);
    }

    @Test
    void recommendationMentionsReviewWhenAmbiguousItemsExist() {
        assertThat(calc.recommendation(RiskLevel.HIGH, 2, 1)).startsWith("Review all ambiguous detections before export.");
        assertThat(calc.recommendation(RiskLevel.LOW, 0, 0)).doesNotContain("ambiguous");
    }
}
