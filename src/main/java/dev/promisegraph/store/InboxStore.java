package dev.promisegraph.store;

import dev.promisegraph.inbox.InboxItem;
import java.util.List;
import java.util.Optional;

/** Notifications people can read and act on in the web app. */
public interface InboxStore {

    /** Insert if absent: the same notification key is stored once. */
    void addItem(InboxItem item);

    Optional<InboxItem> getItem(String tenant, String key);

    /** Newest first. */
    List<InboxItem> inbox(String tenant, String recipient, boolean openOnly);

    /** Marks an open item done. False if it was already done, so a double click acts once. */
    boolean resolve(String tenant, String key, String action);

    /** Everyone who has ever received a notification. */
    List<String> recipients(String tenant);
}
