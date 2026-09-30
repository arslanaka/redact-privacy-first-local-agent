package dev.privacyagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Hosting prices used only for the UI's cost estimates, bound from {@code privacy.deployment.*}.
 * Defaults are Railway's published usage rates; they are estimates, not a bill.
 *
 * @param host                    label shown next to estimates, e.g. "Railway"
 * @param vcpuUsdPerSecond        price of one vCPU for one second
 * @param memoryGbUsdPerSecond    price of one GB of RAM for one second
 * @param modelMemoryGb           RAM the AI service holds while the model is loaded
 * @param otherServicesMemoryGb   RAM of everything else (backend, frontend, database) for the monthly baseline
 */
@ConfigurationProperties(prefix = "privacy.deployment")
public record DeploymentProperties(String host, double vcpuUsdPerSecond, double memoryGbUsdPerSecond,
                                   double modelMemoryGb, double otherServicesMemoryGb) {}
