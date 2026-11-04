package dev.promisegraph.notify;

import java.util.List;

/**
 * An outbound message to a person.
 *
 * @param key idempotency key: one send per key, ever
 * @param actions buttons offered with the message, e.g. "confirm", "dismiss"
 * @param subject what non-promise actions act on: {@code handoff:<id>:<promiseId>} or {@code link:<from>><to>}
 */
public record Notification(String tenant, String recipient, String text, String key, List<String> actions, String promiseId, String subject) {

    public Notification {
        actions = actions == null ? List.of() : List.copyOf(actions);
    }

    /**
     * A notification about one promise, with no subject. A factory rather than a second constructor,
     * so Jackson always has exactly one way to build this record when it crosses Temporal.
     */
    public static Notification about(String tenant, String recipient, String text, String key, List<String> actions, String promiseId) {
        return new Notification(tenant, recipient, text, key, actions, promiseId, null);
    }
}
