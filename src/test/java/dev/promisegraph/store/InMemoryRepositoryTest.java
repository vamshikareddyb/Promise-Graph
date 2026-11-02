package dev.promisegraph.store;

import static org.assertj.core.api.Assertions.assertThat;

import dev.promisegraph.domain.Promise;
import dev.promisegraph.domain.PromiseEvent;
import dev.promisegraph.domain.PromiseStatus;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InMemoryRepositoryTest {

    private static final String NOW = "2026-11-02T05:00:00Z";

    private static Promise promise(String owner, PromiseStatus status, int version) {
        return new Promise("p1", "demo", owner, "send the deck", status, 0.9, NOW, NOW, null, null, "", List.of(), version);
    }

    @Test
    void everyWriteIsIdempotent() {
        InMemoryRepository repo = new InMemoryRepository();

        assertThat(repo.claimMessage("demo", "m1", "batch-a")).isTrue();
        assertThat(repo.claimMessage("demo", "m1", "batch-a")).isTrue(); // retry by the same batch
        assertThat(repo.claimMessage("demo", "m1", "batch-b")).isFalse();

        repo.createPromise(promise("neha", PromiseStatus.DETECTED, 0));
        repo.createPromise(promise("someone-else", PromiseStatus.DETECTED, 0));
        assertThat(repo.getPromise("demo", "p1")).get().extracting(Promise::owner).isEqualTo("neha"); // never overwritten

        assertThat(repo.savePromise(promise("neha", PromiseStatus.ACTIVE, 2))).isTrue();
        assertThat(repo.savePromise(promise("neha", PromiseStatus.DETECTED, 1))).isFalse(); // stale snapshot
        assertThat(repo.getPromise("demo", "p1")).get().extracting(Promise::status).isEqualTo(PromiseStatus.ACTIVE);
        assertThat(repo.openPromises("demo", List.of("neha"))).extracting(Promise::id).containsExactly("p1");

        PromiseEvent e = new PromiseEvent("demo", "p1", "activated", "system", NOW, "p1:2", Map.of());
        assertThat(repo.appendEvent(e)).isTrue();
        assertThat(repo.appendEvent(e)).isFalse();
        assertThat(repo.events("demo", "p1")).hasSize(1);

        assertThat(repo.notificationSent("demo", "k")).isFalse();
        repo.recordNotification("demo", "k", "neha");
        assertThat(repo.notificationSent("demo", "k")).isTrue();
    }
}
