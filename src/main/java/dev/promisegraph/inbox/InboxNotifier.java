package dev.promisegraph.inbox;

import dev.promisegraph.notify.Notification;
import dev.promisegraph.notify.Notifier;
import dev.promisegraph.store.InboxStore;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Delivers notifications to the web inbox, and logs them. */
public final class InboxNotifier implements Notifier {

    private static final Logger log = LoggerFactory.getLogger(InboxNotifier.class);

    private final InboxStore inbox;
    private final Clock clock;

    public InboxNotifier(InboxStore inbox, Clock clock) {
        this.inbox = inbox;
        this.clock = clock;
    }

    @Override
    public void send(Notification n) {
        inbox.addItem(new InboxItem(n.tenant(), n.key(), n.recipient(), n.text(), n.actions(), n.promiseId(), n.subject(),
                Instant.now(clock).toString(), "open", null));
        log.info("to {}: {}{}", n.recipient(), n.text(), n.actions().isEmpty() ? "" : " [" + String.join(" | ", n.actions()) + "]");
    }
}
