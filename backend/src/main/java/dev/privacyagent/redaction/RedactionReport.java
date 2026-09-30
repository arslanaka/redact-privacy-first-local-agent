package dev.privacyagent.redaction;

import java.time.Instant;
import java.util.List;

/**
 * Stored with the document after a successful redaction.
 *
 * @param verificationChecks human-readable list of checks the verifier ran on the generated file
 * @param residuals          approved values that still occur elsewhere in the output un-redacted
 */
public record RedactionReport(
        Instant generatedAt,
        String style,
        String outputFormat,
        int approvedEntities,
        int redactedRegions,
        int redactedCharacters,
        long outputBytes,
        boolean verificationPassed,
        List<String> verificationChecks,
        List<Residual> residuals,
        List<String> limitations) {

    /** An approved value that appears again, un-redacted, outside any approved span. */
    public record Residual(String entityId, String type, int remainingOccurrences) {}
}
