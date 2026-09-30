package dev.privacyagent.review;

import dev.privacyagent.detection.DetectionSource;
import dev.privacyagent.detection.PiiType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** A detection candidate awaiting (or having received) a human review decision. */
@Entity
@Table(name = "detected_entities")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DetectedEntity {

    @Id
    private UUID id;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(nullable = false, columnDefinition = "text")
    private String text;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PiiType type;

    @Column(nullable = false)
    private double confidence;

    @Column(name = "start_offset", nullable = false)
    private int startOffset;

    @Column(name = "end_offset", nullable = false)
    private int endOffset;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DetectionSource source;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", nullable = false)
    private ReviewStatus reviewStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Recommendation recommendation;

    @Column(name = "recommendation_reason", nullable = false)
    private String recommendationReason;

    @Column(name = "regex_confidence")
    private Double regexConfidence;

    @Column(name = "model_confidence")
    private Double modelConfidence;

    @Column(name = "model_labels")
    private String modelLabels;

    @Column(name = "detector_note")
    private String detectorNote;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @SuppressWarnings("java:S107") // a flat factory is clearer than a builder for this one call site
    public static DetectedEntity candidate(UUID documentId, String text, PiiType type, double confidence, int start,
                                           int end, DetectionSource source, Recommendation recommendation,
                                           String reason, Double regexConfidence, Double modelConfidence,
                                           String modelLabels, String detectorNote, Instant now) {
        var e = new DetectedEntity();
        e.id = UUID.randomUUID();
        e.documentId = documentId;
        e.text = text;
        e.type = type;
        e.confidence = confidence;
        e.startOffset = start;
        e.endOffset = end;
        e.source = source;
        e.reviewStatus = ReviewStatus.PENDING; // never auto-approved
        e.recommendation = recommendation;
        e.recommendationReason = reason;
        e.regexConfidence = regexConfidence;
        e.modelConfidence = modelConfidence;
        e.modelLabels = modelLabels;
        e.detectorNote = detectorNote;
        e.createdAt = now;
        return e;
    }

    /** A reviewer-defined redaction. The act of adding it is the approval. */
    public static DetectedEntity manual(UUID documentId, String text, PiiType type, int start, int end, Instant now) {
        var e = candidate(documentId, text, type, 1.0, start, end, DetectionSource.MANUAL, Recommendation.REDACT,
                "Added manually by a reviewer.", null, null, null, null, now);
        e.reviewStatus = ReviewStatus.APPROVED;
        e.reviewedAt = now;
        return e;
    }

    public void review(ReviewStatus decision, Instant now) {
        reviewStatus = decision;
        reviewedAt = decision == ReviewStatus.PENDING ? null : now;
    }

    public void changeType(PiiType newType) {
        type = newType;
    }

    public ConfidenceLevel confidenceLevel() {
        return ConfidenceLevel.of(confidence);
    }

    public boolean overlaps(int start, int end) {
        return startOffset < end && start < endOffset;
    }
}
