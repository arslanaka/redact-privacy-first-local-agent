package dev.privacyagent.detection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Resolves one cluster of overlapping detections.
 *
 * <ul>
 *   <li><b>Rule + model on the same span</b> (overlap covers at least half of the shorter span):
 *       one HYBRID detection. The rule's span and type win, because rule boundaries are exact and
 *       rules validate checksums, while the Laya model card reports span boundaries as its weak point.
 *       <ul>
 *         <li>Same type: confidence = noisy-OR of both signals, 1 - (1 - c_rule)(1 - c_model),
 *             capped at 0.999. Two largely independent detectors agreeing is stronger evidence
 *             than either alone.</li>
 *         <li>Different type: a type conflict. Confidence stays at the rule's value (no boost) and the
 *             entity is flagged so the reviewer is told that the detectors disagreed.</li>
 *       </ul></li>
 *   <li><b>Model only</b>: overlapping model detections are de-duplicated, keeping the most confident.</li>
 *   <li><b>Rule only</b>: kept as is (rules never overlap each other by construction).</li>
 *   <li><b>Model detection only marginally overlapping a rule match</b>: kept as a separate candidate.</li>
 * </ul>
 */
@Component
public class ConflictResolver {

    static final double MAX_CONFIDENCE = 0.999;
    static final double SUBSTANTIAL_OVERLAP = 0.5;

    public List<Detection> resolve(List<Detection> cluster) {
        if (cluster.size() == 1) {
            return List.of(cluster.getFirst());
        }
        List<Detection> rules = cluster.stream().filter(d -> d.source() == DetectionSource.REGEX).toList();
        List<Detection> models = cluster.stream().filter(d -> d.source() == DetectionSource.LAYA).toList();
        List<Detection> out = new ArrayList<>();
        Set<Detection> absorbed = new HashSet<>();
        for (Detection rule : rules) {
            List<Detection> matching = models.stream().filter(m -> substantialOverlap(rule, m)).toList();
            if (matching.isEmpty()) {
                out.add(rule);
                continue;
            }
            absorbed.addAll(matching);
            out.add(combine(rule, matching));
        }
        List<Detection> rest = models.stream().filter(m -> !absorbed.contains(m)).toList();
        out.addAll(dedupe(rest));
        return out;
    }

    private static Detection combine(Detection rule, List<Detection> models) {
        var sameType = models.stream().filter(m -> m.type() == rule.type())
                .max(Comparator.comparingDouble(Detection::confidence));
        List<String> labels = models.stream().flatMap(m -> m.modelLabels().stream()).distinct().toList();
        if (sameType.isPresent()) {
            double c = noisyOr(rule.confidence(), sameType.get().confidence());
            return new Detection(rule.text(), rule.type(), c, rule.start(), rule.end(), DetectionSource.HYBRID,
                    rule.confidence(), sameType.get().confidence(), labels,
                    rule.note() + "; confirmed by local model", false);
        }
        Detection model = models.stream().max(Comparator.comparingDouble(Detection::confidence)).orElseThrow();
        return new Detection(rule.text(), rule.type(), rule.confidence(), rule.start(), rule.end(),
                DetectionSource.HYBRID, rule.confidence(), model.confidence(), labels,
                "type conflict: rule says %s, model says %s; rule type kept".formatted(rule.type(), model.type()), true);
    }

    /** Overlapping model detections: keep the most confident, drop the others. */
    private static List<Detection> dedupe(List<Detection> models) {
        List<Detection> kept = new ArrayList<>();
        models.stream()
                .sorted(Comparator.comparingDouble(Detection::confidence).reversed()
                        .thenComparing(Detection::length, Comparator.reverseOrder()))
                .forEach(d -> {
                    if (kept.stream().noneMatch(d::overlaps)) {
                        kept.add(d);
                    }
                });
        return kept;
    }

    static boolean substantialOverlap(Detection a, Detection b) {
        return a.overlapLength(b) >= SUBSTANTIAL_OVERLAP * Math.min(a.length(), b.length());
    }

    static double noisyOr(double a, double b) {
        return Math.min(MAX_CONFIDENCE, 1 - (1 - a) * (1 - b));
    }
}
