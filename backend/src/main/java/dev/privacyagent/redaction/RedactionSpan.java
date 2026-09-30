package dev.privacyagent.redaction;

import dev.privacyagent.detection.PiiType;

/** A region to redact in the original text (end exclusive). */
public record RedactionSpan(int start, int end, PiiType type) {}
