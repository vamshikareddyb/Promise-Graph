package dev.promisegraph.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Promise titles, bound from {@code promisegraph.titles.*}.
 *
 * @param writer {@code ollama} (default: a free model on this computer writes each new promise's title,
 *     falling back to the rules when Ollama isn't running) or {@code rules} (titles cut from the sentence)
 * @param model the local model for titles; blank means the same model as drafts ({@code PG_OLLAMA_MODEL})
 */
@ConfigurationProperties("promisegraph.titles")
public record TitleProperties(String writer, String model) {

    public TitleProperties {
        writer = writer == null || writer.isBlank() ? "ollama" : writer.strip();
        if (!writer.equals("ollama") && !writer.equals("rules")) {
            throw new IllegalArgumentException("promisegraph.titles.writer must be 'ollama' or 'rules', got " + writer);
        }
        model = model == null ? "" : model.strip();
    }
}
