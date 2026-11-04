package dev.promisegraph.links;

import java.util.Locale;

/**
 * A dependency between two promises: {@code to} cannot be kept until {@code from} is.
 * Suggested links are ignored by ripple until a person confirms them.
 *
 * @param reason why it was suggested, e.g. the clause "once the API is live"
 */
public record Link(
        String tenant,
        String from,
        String to,
        Status status,
        String reason,
        String suggestedBy,
        String decidedBy,
        String createdAt) {

    public enum Status {
        SUGGESTED,
        CONFIRMED,
        REJECTED;

        public String dbValue() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Status fromDb(String value) {
            return valueOf(value.toUpperCase(Locale.ROOT));
        }
    }

    public String key() {
        return from + ">" + to;
    }

    public Link withDecision(Status s, String by) {
        return new Link(tenant, from, to, s, reason, suggestedBy, by, createdAt);
    }
}
