package dev.privacyagent.detection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import org.junit.jupiter.api.Test;

class DetectionMergerTest {

    private final DetectionMerger merger = new DetectionMerger(new ConflictResolver());
    private static final String TEXT = "Contact John Smith at john@example.com or +49 30 1234 5678 in Berlin.";

    private static Detection regex(String value, PiiType type, double c) {
        int s = TEXT.indexOf(value);
        return Detection.regex(value, type, c, s, s + value.length(), "rule");
    }

    private static Detection model(String value, PiiType type, double c) {
        int s = TEXT.indexOf(value);
        return Detection.model(value, type, c, s, s + value.length(), List.of("label"));
    }

    @Test
    void sameSpanSameTypeBecomesHybridWithNoisyOrConfidence() {
        var merged = merger.merge(List.of(regex("john@example.com", PiiType.EMAIL, 0.9)),
                List.of(model("john@example.com", PiiType.EMAIL, 0.8)));
        assertThat(merged).hasSize(1);
        Detection d = merged.getFirst();
        assertThat(d.source()).isEqualTo(DetectionSource.HYBRID);
        assertThat(d.confidence()).isCloseTo(1 - 0.1 * 0.2, within(1e-9));
        assertThat(d.regexConfidence()).isEqualTo(0.9);
        assertThat(d.modelConfidence()).isEqualTo(0.8);
        assertThat(d.typeConflict()).isFalse();
    }

    @Test
    void hybridConfidenceIsCapped() {
        var merged = merger.merge(List.of(regex("john@example.com", PiiType.EMAIL, 0.99)),
                List.of(model("john@example.com", PiiType.EMAIL, 0.999)));
        assertThat(merged.getFirst().confidence()).isEqualTo(ConflictResolver.MAX_CONFIDENCE);
    }

    @Test
    void typeConflictKeepsRuleTypeAndSpanWithoutBoost() {
        var merged = merger.merge(List.of(regex("+49 30 1234 5678", PiiType.PHONE, 0.92)),
                List.of(model("1234 5678", PiiType.ID_NUMBER, 0.95)));
        assertThat(merged).hasSize(1);
        Detection d = merged.getFirst();
        assertThat(d.type()).isEqualTo(PiiType.PHONE);
        assertThat(d.text()).isEqualTo("+49 30 1234 5678");
        assertThat(d.confidence()).isEqualTo(0.92);
        assertThat(d.typeConflict()).isTrue();
        assertThat(d.source()).isEqualTo(DetectionSource.HYBRID);
    }

    @Test
    void nonOverlappingDetectionsPassThroughUnchanged() {
        var merged = merger.merge(List.of(regex("john@example.com", PiiType.EMAIL, 0.97)),
                List.of(model("John Smith", PiiType.PERSON, 0.99), model("Berlin", PiiType.LOCATION, 0.9)));
        assertThat(merged).extracting(Detection::text).containsExactly("John Smith", "john@example.com", "Berlin");
        assertThat(merged).extracting(Detection::source)
                .containsExactly(DetectionSource.LAYA, DetectionSource.REGEX, DetectionSource.LAYA);
    }

    @Test
    void overlappingModelDetectionsAreDeduplicatedByConfidence() {
        var merged = merger.merge(List.of(),
                List.of(model("John Smith", PiiType.PERSON, 0.7), model("Smith", PiiType.PERSON, 0.95)));
        assertThat(merged).extracting(Detection::text).containsExactly("Smith");
    }

    @Test
    void marginalOverlapKeepsBothCandidates() {
        // model span mostly outside the rule span: they describe different things
        String text = "Ref ab@cd.io-customer-reference-long";
        var rule = Detection.regex("ab@cd.io", PiiType.EMAIL, 0.97, 4, 12, "rule");
        var modelHit = Detection.model("io-customer-reference-long", PiiType.OTHER, 0.6, 10, text.length(), List.of());
        assertThat(merger.merge(List.of(rule), List.of(modelHit))).hasSize(2);
    }

    @Test
    void outputIsSortedAndHasNoDuplicateSpans() {
        var merged = merger.merge(
                List.of(regex("john@example.com", PiiType.EMAIL, 0.97), regex("+49 30 1234 5678", PiiType.PHONE, 0.92)),
                List.of(model("+49 30 1234 5678", PiiType.PHONE, 0.99), model("john@example.com", PiiType.EMAIL, 0.99),
                        model("John Smith", PiiType.PERSON, 0.98)));
        assertThat(merged).extracting(Detection::start).isSorted();
        assertThat(merged).extracting(d -> d.start() + ":" + d.end()).doesNotHaveDuplicates();
        assertThat(merged).hasSize(3);
    }

    @Test
    void emptyInputs() {
        assertThat(merger.merge(List.of(), List.of())).isEmpty();
    }
}
