package dev.promisegraph.extraction;

import dev.promisegraph.domain.Commitment;
import dev.promisegraph.domain.Message;
import java.util.List;
import java.util.Set;

/** Turns filtered messages into commitments. */
public interface Extractor {

    /**
     * @param directory the person IDs a commitment may name as owner
     * @throws ExtractionOutputException when a model returns unusable output; retrying may fix it
     */
    List<Commitment> extract(List<Message> messages, Set<String> directory);
}
