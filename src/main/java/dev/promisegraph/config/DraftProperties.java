package dev.promisegraph.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Reply drafts, bound from {@code promisegraph.drafts.*}.
 *
 * @param enabled draft a reply to each email that produced a promise
 * @param writer {@code claude} (default; needs ANTHROPIC_API_KEY) or {@code template}
 * @param model the Claude model for drafts; blank means the same model as extraction
 * @param maxTokens the longest reply Claude may write
 */
@ConfigurationProperties("promisegraph.drafts")
public record DraftProperties(Boolean enabled, String writer, String model, int maxTokens) {

    public DraftProperties {
        enabled = enabled == null || enabled;
        writer = writer == null || writer.isBlank() ? "claude" : writer.strip();
        if (!writer.equals("claude") && !writer.equals("template")) {
            throw new IllegalArgumentException("promisegraph.drafts.writer must be 'claude' or 'template', got " + writer);
        }
        if (maxTokens < 200 || maxTokens > 8000) {
            maxTokens = 1200;
        }
    }
}
