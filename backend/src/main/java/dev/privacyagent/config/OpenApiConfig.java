package dev.privacyagent.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI localPrivacyAgentApi() {
        return new OpenAPI().info(new Info()
                .title("Local Privacy Agent API")
                .version("0.1.0")
                .description("""
                        Upload a document, run hybrid PII detection (deterministic rules + local Laya model), \
                        review every candidate, then generate and download a sanitized copy. \
                        Every step is recorded in an audit trail. Document text is never sent to external LLM APIs."""));
    }
}
