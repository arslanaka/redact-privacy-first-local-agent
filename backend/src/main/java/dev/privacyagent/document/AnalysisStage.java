package dev.privacyagent.document;

/** Fine-grained progress of a running analysis, shown on the processing screen. */
public enum AnalysisStage {
    QUEUED,
    EXTRACTING_TEXT,
    REGEX_DETECTION,
    MODEL_DETECTION,
    MERGING,
    PREPARING_REVIEW,
    DONE
}
