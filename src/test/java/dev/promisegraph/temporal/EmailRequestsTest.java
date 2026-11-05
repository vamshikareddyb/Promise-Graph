package dev.promisegraph.temporal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.promisegraph.domain.Commitment;
import dev.promisegraph.domain.CommitmentKind;
import dev.promisegraph.domain.Message;
import dev.promisegraph.domain.TimingPolicy;
import dev.promisegraph.extraction.Requests;
import dev.promisegraph.extraction.RuleBasedExtractor;
import dev.promisegraph.notify.Notifier;
import dev.promisegraph.store.InMemoryRepository;
import dev.promisegraph.store.InMemoryStores;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** An email asking the mailbox owner to do something becomes the owner's promise, waiting for confirmation. */
class EmailRequestsTest {

    static final Message ASK = new Message("gmail-1", "email:t1", "vamshi@example.com", "2026-11-04T18:32:10Z",
            "You need to submit the project report by friday", "demo");

    PromiseActivitiesImpl activities(Optional<String> owner) {
        Notifier quiet = n -> { };
        return new PromiseActivitiesImpl(new InMemoryRepository(), new InMemoryStores.Links(), quiet, TimingPolicy.defaults(), 0.8,
                null, () -> owner);
    }

    @Test
    void extractionAddsTheRequestForEitherExtractor() {
        List<Commitment> found = ModelActivitiesImpl.withRequests(List.of(ASK), new RuleBasedExtractor().extract(List.of(ASK), java.util.Set.of()));
        assertThat(found).singleElement().satisfies(c -> {
            assertThat(c.kind()).isEqualTo(CommitmentKind.NEW);
            assertThat(c.owner()).isEqualTo(Requests.ADDRESSEE);
        });
    }

    @Test
    void theMailboxOwnerOwesIt() {
        Commitment request = Requests.find(ASK).orElseThrow();
        List<Commitment> assigned = activities(Optional.of("me@gmail.com")).assignRequests(List.of(request));
        assertThat(assigned).singleElement().satisfies(c -> {
            assertThat(c.owner()).isEqualTo("me@gmail.com");
            assertThat(c.recipient()).isEqualTo("vamshi@example.com");
        });
    }

    @Test
    void withoutAMailboxOrForOwnMailItIsDropped() {
        Commitment request = Requests.find(ASK).orElseThrow();
        assertThat(activities(Optional.empty()).assignRequests(List.of(request))).isEmpty();
        assertThat(activities(Optional.of("vamshi@example.com")).assignRequests(List.of(request))).isEmpty();
    }
}
