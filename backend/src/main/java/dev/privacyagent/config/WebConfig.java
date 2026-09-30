package dev.privacyagent.config;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.Executor;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    /** Parallel analyses; the AI service still scores one document at a time, so extra ones wait there. */
    public static final int ANALYSIS_WORKERS = 2;
    public static final int ANALYSIS_QUEUE_CAPACITY = 50;

    private final PrivacyProperties properties;

    public WebConfig(PrivacyProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        var origins = properties.cors().allowedOrigins();
        if (origins == null || origins.isEmpty()) {
            return;
        }
        registry.addMapping("/api/**")
                .allowedOrigins(origins.toArray(String[]::new))
                .allowedMethods("GET", "POST", "PATCH", "DELETE", "OPTIONS")
                .exposedHeaders("X-Request-ID", "Content-Disposition");
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Runs analyses off the request thread. Small on purpose: the local model is the bottleneck. */
    @Bean(name = "analysisExecutor")
    Executor analysisExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(ANALYSIS_WORKERS);
        executor.setMaxPoolSize(ANALYSIS_WORKERS);
        executor.setQueueCapacity(ANALYSIS_QUEUE_CAPACITY);
        executor.setThreadNamePrefix("analysis-");
        executor.setTaskDecorator(mdcPropagatingDecorator());
        executor.initialize();
        return executor;
    }

    /** Carries the request id into the analysis thread so logs of one analysis can be correlated. */
    static TaskDecorator mdcPropagatingDecorator() {
        return runnable -> {
            Map<String, String> context = MDC.getCopyOfContextMap();
            return () -> {
                if (context != null) {
                    MDC.setContextMap(context);
                }
                try {
                    runnable.run();
                } finally {
                    MDC.clear();
                }
            };
        };
    }
}
