package dev.promisegraph.domain;

import java.time.OffsetDateTime;

/**
 * A chat message. {@code ts} is ISO 8601 and must include a time-zone offset.
 * Dates travel as strings so every type crosses Temporal boundaries without custom converters.
 */
public record Message(String id, String channel, String sender, String ts, String text, String tenant) {

    public Message {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("message id is required");
        }
        tenant = tenant == null ? "demo" : tenant;
    }

    /** Parses {@code ts}; throws if it has no offset. */
    public OffsetDateTime timestamp() {
        return OffsetDateTime.parse(ts);
    }
}
