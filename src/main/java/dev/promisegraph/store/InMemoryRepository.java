package dev.promisegraph.store;

import dev.promisegraph.domain.Promise;
import dev.promisegraph.domain.PromiseEvent;
import dev.promisegraph.domain.PromiseStatus;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** For tests and single-process development. Records are immutable, so returning them shares nothing mutable. */
public final class InMemoryRepository implements Repository {

    private record Key(String tenant, String id) {}

    private final Map<Key, String> claims = new HashMap<>();
    private final Map<Key, Promise> promises = new LinkedHashMap<>();
    private final Map<Key, PromiseEvent> events = new LinkedHashMap<>();
    private final Map<Key, String> notifications = new HashMap<>();

    @Override
    public synchronized boolean claimMessage(String tenant, String messageId, String claimant) {
        return claims.computeIfAbsent(new Key(tenant, messageId), k -> claimant).equals(claimant);
    }

    @Override
    public synchronized Optional<Promise> getPromise(String tenant, String promiseId) {
        return Optional.ofNullable(promises.get(new Key(tenant, promiseId)));
    }

    @Override
    public synchronized Set<String> existingIds(String tenant, Collection<String> promiseIds) {
        Set<String> out = new HashSet<>();
        for (String id : promiseIds) {
            if (promises.containsKey(new Key(tenant, id))) {
                out.add(id);
            }
        }
        return out;
    }

    @Override
    public synchronized List<Promise> openPromises(String tenant, Collection<String> owners) {
        return promises.entrySet().stream()
                .filter(e -> e.getKey().tenant().equals(tenant))
                .map(Map.Entry::getValue)
                .filter(p -> owners.contains(p.owner()) && p.stillOpen())
                .toList();
    }

    @Override
    public synchronized List<Promise> promisesByStatus(String tenant, Collection<PromiseStatus> statuses) {
        return promises.entrySet().stream()
                .filter(e -> e.getKey().tenant().equals(tenant))
                .map(Map.Entry::getValue)
                .filter(p -> statuses.contains(p.status()))
                .toList();
    }

    @Override
    public synchronized void createPromise(Promise promise) {
        promises.putIfAbsent(new Key(promise.tenant(), promise.id()), promise);
    }

    @Override
    public synchronized boolean savePromise(Promise promise) {
        Key key = new Key(promise.tenant(), promise.id());
        Promise stored = promises.get(key);
        if (stored != null && stored.version() >= promise.version()) {
            return false;
        }
        promises.put(key, promise);
        return true;
    }

    @Override
    public synchronized boolean appendEvent(PromiseEvent event) {
        return events.putIfAbsent(new Key(event.tenant(), event.idemKey()), event) == null;
    }

    @Override
    public synchronized List<PromiseEvent> events(String tenant, String promiseId) {
        List<PromiseEvent> out = new ArrayList<>();
        for (PromiseEvent e : events.values()) {
            if (e.tenant().equals(tenant) && e.promiseId().equals(promiseId)) {
                out.add(e);
            }
        }
        return out;
    }

    @Override
    public synchronized boolean notificationSent(String tenant, String key) {
        return notifications.containsKey(new Key(tenant, key));
    }

    @Override
    public synchronized void recordNotification(String tenant, String key, String recipient) {
        notifications.putIfAbsent(new Key(tenant, key), recipient);
    }
}
