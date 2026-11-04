package dev.promisegraph;

import dev.promisegraph.domain.Promise;
import dev.promisegraph.domain.PromiseEvent;
import dev.promisegraph.domain.PromiseStatus;
import dev.promisegraph.store.Repository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fails writes at random, after they happen, the worst case for a retry: the work is done but the
 * caller is told it failed. Reads pass through.
 */
public final class FlakyRepository implements Repository {

    private final Repository inner;
    private final double rate;
    private final Random random;
    public final AtomicInteger injected = new AtomicInteger();

    public FlakyRepository(Repository inner, double rate, long seed) {
        this.inner = inner;
        this.rate = rate;
        this.random = new Random(seed);
    }

    private synchronized void maybeFail(String op) {
        if (random.nextDouble() < rate) {
            injected.incrementAndGet();
            throw new IllegalStateException("injected fault after " + op);
        }
    }

    private <T> T after(String op, T result) {
        maybeFail(op);
        return result;
    }

    @Override
    public boolean claimMessage(String tenant, String messageId, String claimant) {
        return after("claimMessage", inner.claimMessage(tenant, messageId, claimant));
    }

    @Override
    public Optional<Promise> getPromise(String tenant, String promiseId) {
        return inner.getPromise(tenant, promiseId);
    }

    @Override
    public Set<String> existingIds(String tenant, Collection<String> promiseIds) {
        return inner.existingIds(tenant, promiseIds);
    }

    @Override
    public List<Promise> openPromises(String tenant, Collection<String> owners) {
        return inner.openPromises(tenant, owners);
    }

    @Override
    public List<Promise> promisesByStatus(String tenant, Collection<PromiseStatus> statuses) {
        return inner.promisesByStatus(tenant, statuses);
    }

    @Override
    public void createPromise(Promise promise) {
        inner.createPromise(promise);
        maybeFail("createPromise");
    }

    @Override
    public boolean savePromise(Promise promise) {
        return after("savePromise", inner.savePromise(promise));
    }

    @Override
    public boolean appendEvent(PromiseEvent event) {
        return after("appendEvent", inner.appendEvent(event));
    }

    @Override
    public List<PromiseEvent> events(String tenant, String promiseId) {
        return inner.events(tenant, promiseId);
    }

    @Override
    public boolean notificationSent(String tenant, String key) {
        return inner.notificationSent(tenant, key);
    }

    @Override
    public void recordNotification(String tenant, String key, String recipient) {
        inner.recordNotification(tenant, key, recipient);
        maybeFail("recordNotification");
    }
}
