package dev.privacyagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Service-to-service protection, bound from {@code privacy.security.*}.
 *
 * @param internalToken         shared secret the frontend proxy sends as {@code X-Internal-Token}; blank disables
 *                              the check (local development only)
 * @param requireInternalToken  refuse to start without a token (set by the {@code prod} profile)
 */
@ConfigurationProperties(prefix = "privacy.security")
public record SecurityProperties(String internalToken, boolean requireInternalToken) {

    public boolean tokenConfigured() {
        return internalToken != null && !internalToken.isBlank();
    }
}
