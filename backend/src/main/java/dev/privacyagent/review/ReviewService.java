package dev.privacyagent.review;

import dev.privacyagent.audit.AuditEventType;
import dev.privacyagent.audit.AuditService;
import dev.privacyagent.common.PrivacyAgentException;
import dev.privacyagent.detection.PiiType;
import dev.privacyagent.document.Document;
import dev.privacyagent.document.DocumentRepository;
import dev.privacyagent.storage.FileStorage;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Human-in-the-loop decisions. Nothing here is automatic: every change is an explicit reviewer action. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewService {

    public static final int CONTEXT_CHARS = 80;

    public record EntityView(DetectedEntity entity, String contextBefore, String contextAfter) {}

    /** Either offsets (start/end) or a text to search for; {@code allOccurrences} applies to text search only. */
    public record ManualRedaction(Integer startOffset, Integer endOffset, String text, PiiType type,
                                  boolean allOccurrences) {}

    private final DocumentRepository documents;
    private final DetectedEntityRepository entities;
    private final AuditService audit;
    private final FileStorage storage;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<EntityView> list(UUID documentId) {
        Document doc = loadDocument(documentId);
        String text = doc.getExtractedText() == null ? "" : doc.getExtractedText();
        return entities.findByDocumentIdOrderByStartOffsetAsc(documentId).stream()
                .map(e -> new EntityView(e, context(text, e.getStartOffset() - CONTEXT_CHARS, e.getStartOffset()),
                        context(text, e.getEndOffset(), e.getEndOffset() + CONTEXT_CHARS)))
                .toList();
    }

    @Transactional
    public DetectedEntity update(UUID entityId, ReviewStatus decision, PiiType newType) {
        if (decision == null && newType == null) {
            throw PrivacyAgentException.validation("Provide reviewStatus and/or type");
        }
        DetectedEntity entity = entities.findById(entityId).orElseThrow(PrivacyAgentException::entityNotFound);
        Document doc = requireReviewable(entity.getDocumentId());
        var now = clock.instant();
        if (newType != null && newType != entity.getType()) {
            PiiType old = entity.getType();
            entity.changeType(newType);
            audit.record(doc.getId(), AuditEventType.ENTITY_TYPE_CHANGED,
                    "Category of %s changed from %s to %s".formatted(AuditService.mask(entity.getText()), old, newType),
                    Map.of("entityId", entityId, "from", old.name(), "to", newType.name()));
        }
        if (decision != null && decision != entity.getReviewStatus()) {
            entity.review(decision, now);
            AuditEventType type = switch (decision) {
                case APPROVED -> AuditEventType.ENTITY_APPROVED;
                case REJECTED -> AuditEventType.ENTITY_REJECTED;
                case PENDING -> AuditEventType.ENTITY_RESET;
            };
            String verb = switch (decision) {
                case APPROVED -> "approved for redaction";
                case REJECTED -> "rejected (will not be redacted)";
                case PENDING -> "reset to pending";
            };
            audit.record(doc.getId(), type, "%s %s %s".formatted(entity.getType(), AuditService.mask(entity.getText()), verb),
                    Map.of("entityId", entityId, "type", entity.getType().name(), "source", entity.getSource().name(),
                            "confidence", entity.getConfidence()));
        }
        syncDocument(doc);
        return entity;
    }

    /** Approves pending items that are HIGH confidence and recommended for redaction. Requires explicit confirmation. */
    @Transactional
    public List<DetectedEntity> approveAllHighConfidence(UUID documentId, boolean confirmed) {
        if (!confirmed) {
            throw PrivacyAgentException.validation("Bulk approval must be confirmed (confirm=true)");
        }
        Document doc = requireReviewable(documentId);
        var now = clock.instant();
        List<DetectedEntity> approved = entities
                .findByDocumentIdAndReviewStatusOrderByStartOffsetAsc(documentId, ReviewStatus.PENDING).stream()
                .filter(e -> e.confidenceLevel() == ConfidenceLevel.HIGH && e.getRecommendation() == Recommendation.REDACT)
                .toList();
        approved.forEach(e -> e.review(ReviewStatus.APPROVED, now));
        audit.record(documentId, AuditEventType.BULK_APPROVED,
                "%d high-confidence items approved in bulk (confirmed by reviewer)".formatted(approved.size()),
                Map.of("count", approved.size(), "entityIds", approved.stream().map(DetectedEntity::getId).toList()));
        syncDocument(doc);
        return approved;
    }

    @Transactional
    public List<DetectedEntity> addManual(UUID documentId, ManualRedaction request) {
        Document doc = requireReviewable(documentId);
        String text = doc.getExtractedText();
        List<int[]> spans = resolveSpans(text, request);
        List<DetectedEntity> existing = entities.findByDocumentIdOrderByStartOffsetAsc(documentId);
        var now = clock.instant();
        List<DetectedEntity> created = new ArrayList<>();
        for (int[] span : spans) {
            boolean alreadyCovered = existing.stream().anyMatch(e -> e.getReviewStatus() == ReviewStatus.APPROVED
                    && e.getStartOffset() <= span[0] && e.getEndOffset() >= span[1]);
            if (alreadyCovered) {
                continue;
            }
            var entity = DetectedEntity.manual(documentId, text.substring(span[0], span[1]), request.type(), span[0],
                    span[1], now);
            created.add(entities.save(entity));
            audit.record(documentId, AuditEventType.MANUAL_ENTITY_ADDED,
                    "Manual redaction added: %s %s".formatted(request.type(), AuditService.mask(entity.getText())),
                    Map.of("entityId", entity.getId(), "type", request.type().name(), "start", span[0], "end", span[1]));
        }
        if (created.isEmpty() && !spans.isEmpty()) {
            throw PrivacyAgentException.validation("That text is already covered by an approved redaction");
        }
        syncDocument(doc);
        return created;
    }

    private List<int[]> resolveSpans(String text, ManualRedaction r) {
        if (r.type() == null) {
            throw PrivacyAgentException.validation("type is required");
        }
        if (r.startOffset() != null || r.endOffset() != null) {
            if (r.startOffset() == null || r.endOffset() == null || r.startOffset() < 0
                    || r.endOffset() <= r.startOffset() || r.endOffset() > text.length()) {
                throw PrivacyAgentException.validation("Offsets must satisfy 0 <= start < end <= text length");
            }
            if (text.substring(r.startOffset(), r.endOffset()).isBlank()) {
                throw PrivacyAgentException.validation("Selected text is blank");
            }
            return List.of(new int[] {r.startOffset(), r.endOffset()});
        }
        if (r.text() == null || r.text().isBlank()) {
            throw PrivacyAgentException.validation("Provide startOffset/endOffset or text");
        }
        List<int[]> spans = new ArrayList<>();
        int from = 0;
        int idx;
        while ((idx = text.indexOf(r.text(), from)) >= 0) {
            spans.add(new int[] {idx, idx + r.text().length()});
            if (!r.allOccurrences()) {
                break;
            }
            from = idx + r.text().length();
        }
        if (spans.isEmpty()) {
            throw PrivacyAgentException.validation("Text not found in the document");
        }
        return spans;
    }

    /** Recomputes REVIEW_REQUIRED/APPROVED and discards an export that no longer matches the decisions. */
    private void syncDocument(Document doc) {
        String outputKey = doc.getRedactedStorageKey();
        long pending = entities.countByDocumentIdAndReviewStatus(doc.getId(), ReviewStatus.PENDING);
        if (doc.syncReviewState(pending, clock.instant()) && outputKey != null) {
            storage.delete(outputKey);
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("reason", "review decision changed after export");
            audit.record(doc.getId(), AuditEventType.OUTPUT_INVALIDATED,
                    "Previously generated redacted file discarded because a review decision changed", meta);
        }
    }

    private Document requireReviewable(UUID documentId) {
        Document doc = loadDocument(documentId);
        if (!doc.getStatus().isReviewable()) {
            throw PrivacyAgentException.invalidState("Document is not in a reviewable state (current: %s)"
                    .formatted(doc.getStatus()));
        }
        return doc;
    }

    private Document loadDocument(UUID id) {
        return documents.findById(id).orElseThrow(PrivacyAgentException::documentNotFound);
    }

    private static String context(String text, int from, int to) {
        return text.substring(Math.max(0, from), Math.min(text.length(), Math.max(0, to)));
    }
}
