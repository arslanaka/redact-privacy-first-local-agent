package dev.privacyagent.detection;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.privacyagent.common.ErrorCode;
import dev.privacyagent.common.PrivacyAgentException;
import dev.privacyagent.config.PrivacyProperties;
import dev.privacyagent.config.RequestIdFilter;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** HTTP client for the local Python AI service. The only place the backend talks to the model. */
@Slf4j
@Component
public class AiServiceClient {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ModelEntity(String text, String type, double confidence, int start, int end, List<String> modelLabels) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DetectResponse(String model, boolean isMock, List<ModelEntity> entities, int candidatesScored,
                                 long processingTimeMs) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    /** From the AI service's /health; the throughput fields are null for the mock or before the warm-up. */
    public record ModelStatus(String status, String model, boolean isMock, String detail, Double spansPerSecond,
                              Integer cpuThreads, Integer maxTextChars) {

        static ModelStatus unavailable(String detail) {
            return new ModelStatus("unavailable", null, false, detail, null, null, null);
        }
    }

    private final RestClient client;

    public AiServiceClient(RestClient.Builder builder, PrivacyProperties properties) {
        // HTTP/1.1 explicitly: the JDK client otherwise attempts an h2c upgrade on plain HTTP,
        // which uvicorn rejects ("Unsupported upgrade request"), dropping the request body.
        var http = java.net.http.HttpClient.newBuilder()
                .version(java.net.http.HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.aiService().connectTimeout())
                .build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(properties.aiService().readTimeout());
        this.client = builder.baseUrl(properties.aiService().baseUrl()).requestFactory(factory).build();
    }

    public DetectResponse detect(String text) {
        try {
            DetectResponse response = client.post().uri("/v1/pii/detect")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(RequestIdFilter.HEADER, requestId())
                    .body(Map.of("text", text))
                    .retrieve()
                    .body(DetectResponse.class);
            if (response == null || response.entities() == null) {
                throw new PrivacyAgentException(ErrorCode.ANALYSIS_FAILED, "AI service returned an empty response");
            }
            return response;
        } catch (ResourceAccessException e) {
            throw PrivacyAgentException.modelUnavailable("AI service unreachable", e);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().isSameCodeAs(HttpStatusCode.valueOf(503))) {
                throw PrivacyAgentException.modelUnavailable("model not ready", e);
            }
            throw new PrivacyAgentException(ErrorCode.ANALYSIS_FAILED,
                    "Local PII model failed (HTTP %d)".formatted(e.getStatusCode().value()), e);
        }
    }

    /** Never throws: reports "unavailable" when the service cannot be reached. */
    public ModelStatus status() {
        try {
            return client.get().uri("/health")
                    .exchange((req, res) -> {
                        var body = res.bodyTo(ModelStatus.class);
                        return body != null ? body : ModelStatus.unavailable("empty response");
                    });
        } catch (RuntimeException e) {
            return ModelStatus.unavailable("AI service unreachable");
        }
    }

    private static String requestId() {
        String id = MDC.get(RequestIdFilter.MDC_KEY);
        return id != null ? id : "analysis";
    }
}
