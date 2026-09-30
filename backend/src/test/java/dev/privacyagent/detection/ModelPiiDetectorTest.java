package dev.privacyagent.detection;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ModelPiiDetectorTest {

    private static AiServiceClient.ModelEntity entity(String text, String type, int start, int end) {
        return new AiServiceClient.ModelEntity(text, type, 0.9, start, end, List.of("given name"));
    }

    @Test
    void convertsPythonCodePointOffsetsToJavaIndices() {
        // The emoji is one code point in Python but two UTF-16 chars in Java.
        String text = "Hi 😀 Anna Keller";
        var d = ModelPiiDetector.toDetection(text, entity("Anna Keller", "PERSON", 5, 16));
        assertThat(d).isNotNull();
        assertThat(d.text()).isEqualTo("Anna Keller");
        assertThat(text.substring(d.start(), d.end())).isEqualTo("Anna Keller");
    }

    @Test
    void rejectsEntitiesWhoseOffsetsDoNotMatchTheText() {
        String text = "Hello Anna";
        assertThat(ModelPiiDetector.toDetection(text, entity("Anna", "PERSON", 0, 4))).isNull();
        assertThat(ModelPiiDetector.toDetection(text, entity("Anna", "PERSON", 6, 99))).isNull();
        assertThat(ModelPiiDetector.toDetection(text, entity("Anna", "PERSON", -1, 4))).isNull();
    }

    @Test
    void rejectsUnknownTypes() {
        assertThat(ModelPiiDetector.toDetection("Hello Anna", entity("Anna", "ALIEN", 6, 10))).isNull();
    }

    @Test
    void clampsConfidence() {
        var e = new AiServiceClient.ModelEntity("Anna", "PERSON", 1.7, 6, 10, List.of());
        assertThat(ModelPiiDetector.toDetection("Hello Anna", e).confidence()).isEqualTo(1.0);
    }
}
