package dev.promisegraph.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Writes notifications to the log. Default for local development. */
public final class LogNotifier implements Notifier {

    private static final Logger log = LoggerFactory.getLogger(LogNotifier.class);

    @Override
    public void send(Notification n) {
        String buttons = n.actions().isEmpty() ? "" : " [" + String.join(" | ", n.actions()) + "]";
        log.info("to {}: {}{}", n.recipient(), n.text(), buttons);
    }
}
