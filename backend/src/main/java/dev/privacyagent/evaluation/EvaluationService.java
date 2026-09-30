package dev.privacyagent.evaluation;

import dev.privacyagent.common.ErrorCode;
import dev.privacyagent.common.PrivacyAgentException;
import dev.privacyagent.config.PrivacyProperties;
import dev.privacyagent.detection.Detection;
import dev.privacyagent.detection.DetectionMerger;
import dev.privacyagent.detection.ModelPiiDetector;
import dev.privacyagent.detection.RegexPiiDetector;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Runs one detection method on raw text without persisting anything. Used by the benchmark in
 * /evaluation so that reported metrics come from the exact production detectors and merger.
 */
@Service
@RequiredArgsConstructor
public class EvaluationService {

    public enum Method { REGEX, MODEL, HYBRID }

    public record Result(Method method, List<Detection> detections, long durationMs, String model, Boolean modelIsMock) {}

    private final RegexPiiDetector regex;
    private final ModelPiiDetector model;
    private final DetectionMerger merger;
    private final PrivacyProperties properties;

    public Result detect(String text, Method method) {
        if (!properties.evaluationEnabled()) {
            throw new PrivacyAgentException(ErrorCode.FEATURE_DISABLED, "Evaluation endpoint is disabled");
        }
        long t0 = System.nanoTime();
        return switch (method) {
            case REGEX -> new Result(method, regex.detect(text), elapsed(t0), null, null);
            case MODEL -> {
                var m = model.detect(text);
                yield new Result(method, m.detections(), elapsed(t0), m.modelName(), m.isMock());
            }
            case HYBRID -> {
                var r = regex.detect(text);
                var m = model.detect(text);
                yield new Result(method, merger.merge(r, m.detections()), elapsed(t0), m.modelName(), m.isMock());
            }
        };
    }

    private static long elapsed(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }
}
