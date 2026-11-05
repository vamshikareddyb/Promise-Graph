package dev.promisegraph.extraction;

import dev.promisegraph.domain.Commitment;
import dev.promisegraph.domain.Message;
import dev.promisegraph.extraction.model.AnthropicExtractor.ModelRequestException;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Claude first; the built-in rules when Claude refuses the request outright (an invalid key, no
 * credit, an unknown model). Retrying can't fix those, and without this every email would end up a
 * dead letter instead of a promise. Busy or unreachable errors still go to the activity's retries.
 */
public final class FallbackExtractor implements Extractor {

    private static final Logger log = LoggerFactory.getLogger(FallbackExtractor.class);

    private final Extractor primary;
    private final Extractor fallback;

    public FallbackExtractor(Extractor primary, Extractor fallback) {
        this.primary = primary;
        this.fallback = fallback;
    }

    @Override
    public List<Commitment> extract(List<Message> messages, Set<String> directory) {
        try {
            return primary.extract(messages, directory);
        } catch (ModelRequestException e) {
            if (e.retryable()) {
                throw e;
            }
            log.warn("Claude refused promise extraction ({}); used the built-in rules for these {} message(s). "
                    + "Fix or remove the API key to stop this warning.", e.getMessage(), messages.size());
            return fallback.extract(messages, directory);
        }
    }
}
