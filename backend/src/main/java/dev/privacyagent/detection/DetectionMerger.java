package dev.privacyagent.detection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Merges rule and model detections into one list of unique candidates.
 *
 * <p>Algorithm:
 * <ol>
 *   <li>Sort all detections by start offset (longest first on ties).</li>
 *   <li>Sweep left to right and group transitively overlapping detections into clusters.
 *       Non-overlapping detections form singleton clusters and pass through unchanged.</li>
 *   <li>Hand each cluster to the {@link ConflictResolver}, which decides spans, types, source
 *       (REGEX / LAYA / HYBRID) and the combined confidence.</li>
 * </ol>
 * The output never contains two detections with identical spans. Detections from different
 * detectors may still partially overlap when they clearly describe different things; the
 * redaction engine unions overlapping approved spans, so this is safe.
 */
@Component
@RequiredArgsConstructor
public class DetectionMerger {

    private final ConflictResolver resolver;

    public List<Detection> merge(List<Detection> regex, List<Detection> model) {
        List<Detection> all = Stream.concat(regex.stream(), model.stream())
                .sorted(Comparator.comparingInt(Detection::start).thenComparing(Detection::end, Comparator.reverseOrder()))
                .toList();
        List<Detection> merged = new ArrayList<>();
        List<Detection> cluster = new ArrayList<>();
        int clusterEnd = -1;
        for (Detection d : all) {
            if (!cluster.isEmpty() && d.start() >= clusterEnd) {
                merged.addAll(resolver.resolve(cluster));
                cluster = new ArrayList<>();
            }
            cluster.add(d);
            clusterEnd = Math.max(clusterEnd, d.end());
        }
        if (!cluster.isEmpty()) {
            merged.addAll(resolver.resolve(cluster));
        }
        merged.sort(Comparator.comparingInt(Detection::start).thenComparingInt(Detection::end));
        return merged;
    }
}
