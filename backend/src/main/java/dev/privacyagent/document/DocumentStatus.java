package dev.privacyagent.document;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Document lifecycle. Allowed transitions are declared here, in one place, and enforced by
 * {@link Document#transitionTo}. Anything else is an INVALID_WORKFLOW_STATE error.
 *
 * <pre>
 * UPLOADED -> EXTRACTING -> ANALYSING -> REVIEW_REQUIRED <-> APPROVED -> REDACTING -> COMPLETED
 *                  \             \                                           \
 *                   +-> FAILED <--+------------------------------------------+
 * </pre>
 * COMPLETED goes back to REVIEW_REQUIRED / APPROVED when a review decision changes after export,
 * which invalidates the generated file. FAILED can be retried (back to EXTRACTING).
 */
public enum DocumentStatus {
    UPLOADED,
    EXTRACTING,
    ANALYSING,
    REVIEW_REQUIRED,
    APPROVED,
    REDACTING,
    COMPLETED,
    FAILED;

    private static final Map<DocumentStatus, Set<DocumentStatus>> TRANSITIONS = Map.of(
            UPLOADED, EnumSet.of(EXTRACTING),
            EXTRACTING, EnumSet.of(ANALYSING, FAILED),
            ANALYSING, EnumSet.of(REVIEW_REQUIRED, APPROVED, FAILED),
            REVIEW_REQUIRED, EnumSet.of(APPROVED),
            APPROVED, EnumSet.of(REVIEW_REQUIRED, REDACTING),
            REDACTING, EnumSet.of(COMPLETED, FAILED),
            COMPLETED, EnumSet.of(REVIEW_REQUIRED, APPROVED, REDACTING),
            FAILED, EnumSet.of(EXTRACTING));

    public boolean canTransitionTo(DocumentStatus target) {
        return TRANSITIONS.getOrDefault(this, Set.of()).contains(target);
    }

    /** States in which reviewers may change decisions or add manual redactions. */
    public boolean isReviewable() {
        return this == REVIEW_REQUIRED || this == APPROVED || this == COMPLETED;
    }

    public boolean canStartAnalysis() {
        return this == UPLOADED || this == FAILED;
    }
}
