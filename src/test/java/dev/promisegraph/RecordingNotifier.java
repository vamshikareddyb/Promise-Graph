package dev.promisegraph;

import dev.promisegraph.notify.Notification;
import dev.promisegraph.notify.Notifier;
import java.util.ArrayList;
import java.util.List;

/** Keeps every notification in memory for assertions. */
public final class RecordingNotifier implements Notifier {

    private final List<Notification> sent = new ArrayList<>();

    @Override
    public synchronized void send(Notification notification) {
        sent.add(notification);
    }

    public synchronized List<String> keys() {
        return sent.stream().map(Notification::key).toList();
    }

    public synchronized int count() {
        return sent.size();
    }

    public synchronized List<String> textsFor(String recipient) {
        return sent.stream().filter(n -> n.recipient().equals(recipient)).map(Notification::text).toList();
    }

    public synchronized long countStartingWith(String recipient, String prefix) {
        return textsFor(recipient).stream().filter(t -> t.startsWith(prefix)).count();
    }
}
