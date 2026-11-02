package dev.promisegraph.ingestion;

import dev.promisegraph.domain.Message;
import java.util.List;

/** One batch of messages for an extraction workflow. */
public record ExtractionInput(String tenant, List<Message> messages) {

    public ExtractionInput {
        messages = List.copyOf(messages);
    }
}
