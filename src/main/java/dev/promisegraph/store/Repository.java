package dev.promisegraph.store;

import dev.promisegraph.domain.Promise;
import dev.promisegraph.domain.PromiseEvent;
import dev.promisegraph.domain.PromiseStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Storage used by the activities. Every write is idempotent so retried activities are safe. */
public interface Repository {

    /**
     * True if this claimant may process the message: it is new, or this claimant already
     * claimed it (a retry). False if another batch claimed it first.
     */
    boolean claimMessage(String tenant, String messageId, String claimant);

    Optional<Promise> getPromise(String tenant, String promiseId);

    Set<String> existingIds(String tenant, Collection<String> promiseIds);

    List<Promise> openPromises(String tenant, Collection<String> owners);

    List<Promise> promisesByStatus(String tenant, Collection<PromiseStatus> statuses);

    /** Insert if absent; never overwrite (the workflow owns the state after creation). */
    void createPromise(Promise promise);

    /** Write the workflow's snapshot if its version is newer than the stored one. */
    boolean savePromise(Promise promise);

    /** Append once per idempotency key. True if newly written. */
    boolean appendEvent(PromiseEvent event);

    List<PromiseEvent> events(String tenant, String promiseId);

    boolean notificationSent(String tenant, String key);

    void recordNotification(String tenant, String key, String recipient);
}
