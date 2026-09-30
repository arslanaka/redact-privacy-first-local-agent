package dev.privacyagent.detection;

import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Model-based detection through the AI service. Model output is not trusted blindly: every entity's
 * offsets are converted (Python code points to Java UTF-16) and checked against the text; entities
 * with unknown types or offsets that do not match are dropped and counted.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelPiiDetector {

    public record Result(List<Detection> detections, String modelName, boolean isMock, int candidatesScored,
                         long modelTimeMs, int rejected) {}

    private final AiServiceClient client;

    public Result detect(String text) {
        var response = client.detect(text);
        List<Detection> detections = new ArrayList<>();
        int rejected = 0;
        for (var e : response.entities()) {
            Detection d = toDetection(text, e);
            if (d == null) {
                rejected++;
            } else {
                detections.add(d);
            }
        }
        if (rejected > 0) {
            log.warn("model returned invalid entities count={} (bad offsets or unknown type); they were dropped", rejected);
        }
        return new Result(detections, response.model(), response.isMock(), response.candidatesScored(),
                response.processingTimeMs(), rejected);
    }

    static Detection toDetection(String text, AiServiceClient.ModelEntity e) {
        PiiType type;
        try {
            type = PiiType.valueOf(e.type());
        } catch (IllegalArgumentException | NullPointerException ex) {
            return null;
        }
        int codePoints = text.codePointCount(0, text.length());
        if (e.start() < 0 || e.end() <= e.start() || e.end() > codePoints) {
            return null;
        }
        int start = text.offsetByCodePoints(0, e.start());
        int end = text.offsetByCodePoints(start, e.end() - e.start());
        String span = text.substring(start, end);
        if (e.text() != null && !span.equals(e.text())) {
            return null;
        }
        double confidence = Math.clamp(e.confidence(), 0.0, 1.0);
        return Detection.model(span, type, confidence, start, end, e.modelLabels());
    }
}
