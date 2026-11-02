package dev.promisegraph.notify;

/** Sends notifications. Slack comes in a later task; the interface stays the same. */
public interface Notifier {

    void send(Notification notification);
}
