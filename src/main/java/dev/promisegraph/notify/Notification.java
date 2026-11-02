package dev.promisegraph.notify;

import java.util.List;

/**
 * An outbound message to a person.
 *
 * @param key idempotency key: one send per key, ever
 * @param actions buttons offered with the message, e.g. "confirm", "dismiss"
 */
public record Notification(String tenant, String recipient, String text, String key, List<String> actions, String promiseId) {

    public Notification {
        actions = actions == null ? List.of() : List.copyOf(actions);
    }
}
