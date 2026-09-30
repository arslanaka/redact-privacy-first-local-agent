package dev.privacyagent.review;

/** What the system suggests a reviewer does. A suggestion only: every entity still needs a human decision. */
public enum Recommendation {
    REDACT,
    REVIEW,
    LIKELY_KEEP
}
