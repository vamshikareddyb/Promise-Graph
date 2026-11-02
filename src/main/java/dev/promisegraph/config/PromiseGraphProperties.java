package dev.promisegraph.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Runtime settings, bound from {@code promisegraph.*} in application.yml or the environment. */
@ConfigurationProperties("promisegraph")
public record PromiseGraphProperties(
        String tenant,
        String store,
        String extractor,
        String model,
        double confidenceThreshold,
        int llmMaxConcurrency,
        String webhookToken,
        Temporal temporal,
        Gateway gateway) {

    public static final String TASK_QUEUE_MAIN = "pg-main";
    public static final String TASK_QUEUE_LLM = "pg-llm";

    public PromiseGraphProperties {
        if (!"memory".equals(store) && !"jdbc".equals(store)) {
            throw new IllegalArgumentException("promisegraph.store must be 'memory' or 'jdbc', got " + store);
        }
        if (!"rules".equals(extractor) && !"llm".equals(extractor)) {
            throw new IllegalArgumentException("promisegraph.extractor must be 'rules' or 'llm', got " + extractor);
        }
        if (confidenceThreshold < 0 || confidenceThreshold > 1) {
            throw new IllegalArgumentException("promisegraph.confidence-threshold must be between 0 and 1");
        }
    }

    public record Temporal(String address, String namespace, boolean workerEnabled) {}

    public record Gateway(int maxBatch, Duration maxWait) {}
}
