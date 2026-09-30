package dev.privacyagent.api;

import dev.privacyagent.api.ApiDtos.Capacity;
import dev.privacyagent.api.ApiDtos.Cost;
import dev.privacyagent.api.ApiDtos.SystemStatus;
import dev.privacyagent.config.DeploymentProperties;
import dev.privacyagent.config.PrivacyProperties;
import dev.privacyagent.config.WebConfig;
import dev.privacyagent.detection.AiServiceClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "System", description = "Backend and local model status for the UI")
@RestController
@RequiredArgsConstructor
public class SystemController {

    private final AiServiceClient aiService;
    private final PrivacyProperties properties;
    private final DeploymentProperties deployment;

    @Operation(summary = "Backend status, local model readiness and upload limits")
    @GetMapping("/api/system/status")
    public SystemStatus status() {
        var model = aiService.status();
        var capacity = new Capacity(model.spansPerSecond(), model.cpuThreads(), model.maxTextChars(),
                properties.aiService().readTimeout().toSeconds(), WebConfig.ANALYSIS_WORKERS,
                WebConfig.ANALYSIS_QUEUE_CAPACITY);
        var cost = new Cost(deployment.host(), deployment.vcpuUsdPerSecond(), deployment.memoryGbUsdPerSecond(),
                deployment.modelMemoryGb(), deployment.otherServicesMemoryGb());
        return new SystemStatus("UP", model.status(), model.model(), model.isMock(), model.detail(),
                properties.upload().maxFileSize().toBytes(), List.of(".pdf", ".txt", ".md"),
                properties.retention().documentHours(), properties.redaction().style().name(), capacity, cost);
    }
}
