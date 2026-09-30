package dev.privacyagent.common;

import dev.privacyagent.config.RequestIdFilter;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** Structured error body returned by every endpoint. */
    public record ApiError(String error, String message, int status, String requestId, Instant timestamp,
                           Map<String, Object> details) {}

    @ExceptionHandler(PrivacyAgentException.class)
    ResponseEntity<ApiError> handle(PrivacyAgentException ex) {
        if (ex.code().status().is5xxServerError()) {
            log.warn("request failed code={} message={}", ex.code(), ex.getMessage(), ex.getCause());
        }
        return build(ex.code(), ex.getMessage(), ex.details());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiError> handleTooLarge(MaxUploadSizeExceededException ex) {
        return build(ErrorCode.DOCUMENT_TOO_LARGE, "Document exceeds the maximum upload size", Map.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, Object> fields = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(fe -> fe.getField(), fe -> String.valueOf(fe.getDefaultMessage()), (a, b) -> a));
        return build(ErrorCode.VALIDATION_FAILED, "Request validation failed", Map.of("fields", fields));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestPartException.class, MissingServletRequestParameterException.class})
    ResponseEntity<ApiError> handleBadRequest(Exception ex) {
        return build(ErrorCode.VALIDATION_FAILED, "Malformed request", Map.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> handleMethod(HttpRequestMethodNotSupportedException ex) {
        return ResponseEntity.status(405).body(new ApiError("METHOD_NOT_ALLOWED", ex.getMessage(), 405,
                MDC.get(RequestIdFilter.MDC_KEY), Instant.now(), Map.of()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> handleNoResource(NoResourceFoundException ex) {
        return ResponseEntity.status(404).body(new ApiError("NOT_FOUND", "No such endpoint", 404,
                MDC.get(RequestIdFilter.MDC_KEY), Instant.now(), Map.of()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        log.error("unexpected error", ex);
        return build(ErrorCode.INTERNAL_ERROR, "Unexpected server error", Map.of());
    }

    private ResponseEntity<ApiError> build(ErrorCode code, String message, Map<String, Object> details) {
        var body = new ApiError(code.name(), message, code.status().value(), MDC.get(RequestIdFilter.MDC_KEY),
                Instant.now(), details);
        return ResponseEntity.status(code.status()).body(body);
    }
}
