package dev.promisegraph.inbox;

import java.util.List;

/**
 * A notification waiting in someone's inbox, with the buttons they can press.
 *
 * @param key the notification's idempotency key, which is also its id
 * @param subject what non-promise actions refer to: {@code handoff:<id>:<promiseId>} or {@code link:<from>><to>}
 * @param status {@code open} until an action is taken, then {@code done}
 */
public record InboxItem(
        String tenant,
        String key,
        String recipient,
        String text,
        List<String> actions,
        String promiseId,
        String subject,
        String createdAt,
        String status,
        String resolvedAction) {

    public InboxItem {
        actions = actions == null ? List.of() : List.copyOf(actions);
    }

    public boolean open() {
        return "open".equals(status);
    }

    public InboxItem resolved(String action) {
        return new InboxItem(tenant, key, recipient, text, actions, promiseId, subject, createdAt, "done", action);
    }
}
