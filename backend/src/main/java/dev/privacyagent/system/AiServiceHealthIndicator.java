package dev.privacyagent.system;

import dev.privacyagent.detection.AiServiceClient;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/** Exposes the local model's readiness under /actuator/health (component "aiService"). */
@Component("aiService")
@RequiredArgsConstructor
public class AiServiceHealthIndicator implements HealthIndicator {

    private final AiServiceClient client;

    @Override
    public Health health() {
        var status = client.status();
        Health.Builder builder = switch (status.status()) {
            case "ready" -> Health.up();
            case "loading" -> Health.outOfService();
            default -> Health.down();
        };
        builder.withDetail("modelStatus", status.status()).withDetail("isMock", status.isMock());
        if (status.model() != null) {
            builder.withDetail("model", status.model());
        }
        if (status.detail() != null) {
            builder.withDetail("detail", status.detail());
        }
        return builder.build();
    }
}
