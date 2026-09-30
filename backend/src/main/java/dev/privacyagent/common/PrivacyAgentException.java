package dev.privacyagent.common;

import java.util.Map;

/**
 * The single application exception type. The {@link ErrorCode} decides the HTTP status and the
 * message is safe to show to users: it must never contain document content.
 */
public class PrivacyAgentException extends RuntimeException {

    private final ErrorCode code;
    private final Map<String, Object> details;

    public PrivacyAgentException(ErrorCode code, String message) {
        this(code, message, Map.of(), null);
    }

    public PrivacyAgentException(ErrorCode code, String message, Throwable cause) {
        this(code, message, Map.of(), cause);
    }

    public PrivacyAgentException(ErrorCode code, String message, Map<String, Object> details, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.details = details;
    }

    public ErrorCode code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }

    public static PrivacyAgentException unsupportedType(String message) {
        return new PrivacyAgentException(ErrorCode.UNSUPPORTED_DOCUMENT_TYPE, message);
    }

    public static PrivacyAgentException tooLarge(long maxBytes) {
        return new PrivacyAgentException(ErrorCode.DOCUMENT_TOO_LARGE,
                "Document exceeds the maximum size of %d MB".formatted(maxBytes / (1024 * 1024)),
                Map.of("maxBytes", maxBytes), null);
    }

    public static PrivacyAgentException extractionFailed(String message, Throwable cause) {
        return new PrivacyAgentException(ErrorCode.TEXT_EXTRACTION_FAILED, message, cause);
    }

    public static PrivacyAgentException modelUnavailable(String detail, Throwable cause) {
        return new PrivacyAgentException(ErrorCode.MODEL_UNAVAILABLE, "Local PII model is currently unavailable.",
                detail == null ? Map.of() : Map.of("detail", detail), cause);
    }

    public static PrivacyAgentException invalidState(String message) {
        return new PrivacyAgentException(ErrorCode.INVALID_WORKFLOW_STATE, message);
    }

    public static PrivacyAgentException documentNotFound() {
        return new PrivacyAgentException(ErrorCode.DOCUMENT_NOT_FOUND, "Document not found");
    }

    public static PrivacyAgentException entityNotFound() {
        return new PrivacyAgentException(ErrorCode.ENTITY_NOT_FOUND, "Detected entity not found");
    }

    public static PrivacyAgentException validation(String message) {
        return new PrivacyAgentException(ErrorCode.VALIDATION_FAILED, message);
    }
}
