package dev.privacyagent.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/** All application-specific configuration, bound from the {@code privacy.*} namespace. */
@Validated
@ConfigurationProperties(prefix = "privacy")
public record PrivacyProperties(
        @NotNull Storage storage,
        @NotNull Upload upload,
        @NotNull Retention retention,
        @NotNull AiService aiService,
        @NotNull Redaction redaction,
        @NotNull Cors cors,
        boolean evaluationEnabled) {

    public record Storage(@NotBlank String directory) {}

    public record Upload(@NotNull DataSize maxFileSize) {}

    /** Documents (files, text, detections) older than {@code documentHours} are deleted automatically. */
    public record Retention(@Min(1) int documentHours, @NotNull Duration cleanupInterval) {}

    public record AiService(@NotBlank String baseUrl, @NotNull Duration connectTimeout, @NotNull Duration readTimeout) {}

    public record Redaction(@NotNull RedactionStyle style) {}

    public record Cors(List<String> allowedOrigins) {}

    public enum RedactionStyle {
        /** Replace each character with a full block, preserving line breaks: "Markus" becomes "██████". */
        BLOCK,
        /** Replace the whole span with a type label: "Markus Weber" becomes "[REDACTED PERSON]". */
        LABEL
    }
}
