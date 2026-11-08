package dev.promisegraph.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Reply drafts, bound from {@code promisegraph.drafts.*}.
 *
 * @param auto also draft a reply to every email that brings a promise, without being asked
 *     (off by default: drafts are written when the person asks for a follow-up on a promise)
 * @param writer {@code claude} (default; needs ANTHROPIC_API_KEY), {@code ollama} (a free model on this
 *     computer, through Ollama) or {@code template}
 * @param model the Claude model for drafts; blank means the same model as extraction
 * @param maxTokens the longest reply the model may write
 * @param ollamaUrl where Ollama listens
 * @param ollamaModel the local model to use, e.g. {@code gemma4:e4b}
 */
@ConfigurationProperties("promisegraph.drafts")
public record DraftProperties(Boolean auto, String writer, String model, int maxTokens, String ollamaUrl, String ollamaModel) {

    public DraftProperties {
        auto = auto != null && auto;
        writer = writer == null || writer.isBlank() ? "claude" : writer.strip();
        if (!writer.equals("claude") && !writer.equals("ollama") && !writer.equals("template")) {
            throw new IllegalArgumentException("promisegraph.drafts.writer must be 'claude', 'ollama' or 'template', got " + writer);
        }
        ollamaUrl = ollamaUrl == null || ollamaUrl.isBlank() ? "http://localhost:11434" : ollamaUrl.strip();
        ollamaModel = ollamaModel == null || ollamaModel.isBlank() ? "gemma4:e4b" : ollamaModel.strip();
        if (maxTokens < 200 || maxTokens > 8000) {
            maxTokens = 1200;
        }
    }
}
