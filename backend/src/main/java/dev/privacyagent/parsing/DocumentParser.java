package dev.privacyagent.parsing;

import dev.privacyagent.document.DocumentFormat;

/**
 * Extracts plain text from one document format. Offsets of every later stage (detection,
 * review, redaction) refer to the text returned here, so implementations must be deterministic.
 * Line endings are normalised to "\n".
 */
public interface DocumentParser {

    DocumentFormat format();

    /** Cheap content check (magic bytes / encoding) used during upload validation. */
    boolean looksValid(byte[] content);

    String extractText(byte[] content);
}
