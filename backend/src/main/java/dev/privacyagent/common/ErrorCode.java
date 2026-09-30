package dev.privacyagent.common;

import org.springframework.http.HttpStatus;

/** Stable, machine-readable error codes. The frontend maps these to human-readable messages. */
public enum ErrorCode {
    UNSUPPORTED_DOCUMENT_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    DOCUMENT_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE),
    TEXT_EXTRACTION_FAILED(HttpStatus.UNPROCESSABLE_ENTITY),
    MODEL_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
    ANALYSIS_FAILED(HttpStatus.INTERNAL_SERVER_ERROR),
    REDACTION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR),
    INVALID_WORKFLOW_STATE(HttpStatus.CONFLICT),
    DOCUMENT_NOT_FOUND(HttpStatus.NOT_FOUND),
    ENTITY_NOT_FOUND(HttpStatus.NOT_FOUND),
    OUTPUT_NOT_AVAILABLE(HttpStatus.NOT_FOUND),
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    FEATURE_DISABLED(HttpStatus.NOT_FOUND),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
