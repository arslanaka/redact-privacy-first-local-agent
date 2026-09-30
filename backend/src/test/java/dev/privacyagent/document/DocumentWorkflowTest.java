package dev.privacyagent.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.privacyagent.common.ErrorCode;
import dev.privacyagent.common.PrivacyAgentException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DocumentWorkflowTest {

    private final Instant now = Instant.parse("2026-01-01T10:00:00Z");

    private Document doc() {
        return Document.uploaded(UUID.randomUUID(), "a.txt", "text/plain", DocumentFormat.TXT, 10, "k.txt", now);
    }

    @Test
    void happyPathTransitions() {
        var d = doc();
        d.startAnalysis(now);
        assertThat(d.getStatus()).isEqualTo(DocumentStatus.EXTRACTING);
        d.transitionTo(DocumentStatus.ANALYSING, now);
        d.completeAnalysis(null, true, now);
        assertThat(d.getStatus()).isEqualTo(DocumentStatus.REVIEW_REQUIRED);
        d.syncReviewState(0, now);
        assertThat(d.getStatus()).isEqualTo(DocumentStatus.APPROVED);
        d.startRedaction(now);
        d.completeRedaction("out.txt", null, now);
        assertThat(d.getStatus()).isEqualTo(DocumentStatus.COMPLETED);
    }

    @Test
    void analysisWithoutDetectionsGoesStraightToApproved() {
        var d = doc();
        d.startAnalysis(now);
        d.transitionTo(DocumentStatus.ANALYSING, now);
        d.completeAnalysis(null, false, now);
        assertThat(d.getStatus()).isEqualTo(DocumentStatus.APPROVED);
    }

    @Test
    void illegalTransitionsAreRejected() {
        var d = doc();
        assertThatThrownBy(() -> d.transitionTo(DocumentStatus.COMPLETED, now))
                .isInstanceOf(PrivacyAgentException.class)
                .extracting(e -> ((PrivacyAgentException) e).code()).isEqualTo(ErrorCode.INVALID_WORKFLOW_STATE);
        assertThatThrownBy(() -> d.startRedaction(now)).isInstanceOf(PrivacyAgentException.class);
    }

    @Test
    void analysisCannotStartTwice() {
        var d = doc();
        d.startAnalysis(now);
        assertThatThrownBy(() -> d.startAnalysis(now)).isInstanceOf(PrivacyAgentException.class);
    }

    @Test
    void failedDocumentsCanBeRetried() {
        var d = doc();
        d.startAnalysis(now);
        d.fail(ErrorCode.MODEL_UNAVAILABLE, "model down", now);
        assertThat(d.getStatus()).isEqualTo(DocumentStatus.FAILED);
        assertThat(d.getErrorCode()).isEqualTo(ErrorCode.MODEL_UNAVAILABLE);
        d.startAnalysis(now);
        assertThat(d.getStatus()).isEqualTo(DocumentStatus.EXTRACTING);
        assertThat(d.getErrorCode()).isNull();
    }

    @Test
    void changingAReviewAfterExportInvalidatesTheOutput() {
        var d = doc();
        d.startAnalysis(now);
        d.transitionTo(DocumentStatus.ANALYSING, now);
        d.completeAnalysis(null, false, now);
        d.startRedaction(now);
        d.completeRedaction("out.txt", null, now);
        boolean invalidated = d.syncReviewState(1, now);
        assertThat(invalidated).isTrue();
        assertThat(d.getRedactedStorageKey()).isNull();
        assertThat(d.getStatus()).isEqualTo(DocumentStatus.REVIEW_REQUIRED);
    }

    @Test
    void reviewableStates() {
        assertThat(DocumentStatus.REVIEW_REQUIRED.isReviewable()).isTrue();
        assertThat(DocumentStatus.COMPLETED.isReviewable()).isTrue();
        assertThat(DocumentStatus.ANALYSING.isReviewable()).isFalse();
        assertThat(DocumentStatus.UPLOADED.isReviewable()).isFalse();
    }
}
