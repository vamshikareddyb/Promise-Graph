package dev.promisegraph.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.promisegraph.domain.Message;
import dev.promisegraph.extraction.model.AnthropicExtractor.ModelRequestException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** A rejected Claude key must not stop promises from being found. */
class FallbackExtractorTest {

    static final Message EMAIL = new Message("gmail-1", "email:t", "vamshika496@gmail.com", "2026-11-05T18:00:00Z",
            "I will send the project report by friday", "demo");

    @Test
    void aRefusedRequestFallsBackToTheRules() {
        Extractor refused = (ms, d) -> {
            throw new ModelRequestException("model API rejected the request: 401 (invalid x-api-key)", false, null);
        };
        assertThat(new FallbackExtractor(refused, new RuleBasedExtractor()).extract(List.of(EMAIL), Set.of()))
                .singleElement().satisfies(c -> assertThat(c.what()).isEqualTo("send the project report"));
    }

    @Test
    void aBusyModelIsStillRetried() {
        Extractor busy = (ms, d) -> {
            throw new ModelRequestException("model API returned 529", true, null);
        };
        assertThatThrownBy(() -> new FallbackExtractor(busy, new RuleBasedExtractor()).extract(List.of(EMAIL), Set.of()))
                .isInstanceOf(ModelRequestException.class);
    }
}
