package dev.promisegraph.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.promisegraph.links.Link;
import dev.promisegraph.store.InMemoryStores;
import dev.promisegraph.temporal.Contracts.HandoffResponse;
import dev.promisegraph.temporal.Contracts.PromiseSignal;
import dev.promisegraph.temporal.NegotiationContracts.Vote;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class InboxServiceTest {

    private final InMemoryStores.Inbox inbox = new InMemoryStores.Inbox();
    private final InMemoryStores.Links links = new InMemoryStores.Links();
    private final List<Object> sent = new ArrayList<>();
    private boolean closed;

    private final Signals signals = new Signals() {
        @Override
        public void toPromise(String promiseId, PromiseSignal signal) {
            if (closed) {
                throw new ClosedException("that promise is already closed");
            }
            sent.add(promiseId + " " + signal.kind() + " " + signal.due() + " " + signal.sourceId());
        }

        @Override
        public void toHandoff(String handoffId, HandoffResponse r) {
            sent.add(handoffId + " " + r.kind() + " " + r.promiseId() + " " + r.by());
        }

        @Override
        public void toNegotiation(String negotiationId, Vote v) {
            sent.add(negotiationId + " " + v.kind() + " r" + v.round() + " " + v.planId() + " " + v.by() + " " + v.reason() + " " + v.sourceId());
        }
    };

    private final InboxService service = new InboxService(inbox, links, signals);

    private void item(String key, List<String> actions, String promiseId, String subject) {
        inbox.addItem(new InboxItem("demo", key, "dev", "text", actions, promiseId, subject, "2026-11-10T04:00:00Z", "open", null));
    }

    @Test
    void promiseActionsBecomeSignalsOnce() {
        item("k1", List.of("keep date", "new date"), "p1", null);
        service.act("demo", "k1", new InboxService.ActionRequest("new date", null, "2026-12-02", null));
        assertThat(sent).containsExactly("p1 slip 2026-12-02 inbox:k1:new date");
        assertThat(inbox.getItem("demo", "k1")).get().satisfies(i -> {
            assertThat(i.open()).isFalse();
            assertThat(i.resolvedAction()).isEqualTo("new date");
        });
        assertThatThrownBy(() -> service.act("demo", "k1", new InboxService.ActionRequest("keep date", null, null, null)))
                .isInstanceOf(InboxService.ActionException.class).hasMessageStartingWith("already done");
    }

    @Test
    void validatesActionsAndDates() {
        item("k2", List.of("kept", "slip"), "p1", null);
        assertThatThrownBy(() -> service.act("demo", "k2", new InboxService.ActionRequest("dismiss", null, null, null)))
                .hasMessageContaining("offers");
        assertThatThrownBy(() -> service.act("demo", "k2", new InboxService.ActionRequest("slip", null, null, null)))
                .hasMessageContaining("needs a date");
        assertThatThrownBy(() -> service.act("demo", "k2", new InboxService.ActionRequest("slip", null, "next friday", null)))
                .hasMessageContaining("2026-11-20");
        assertThat(sent).isEmpty();
        assertThat(inbox.getItem("demo", "k2")).get().satisfies(i -> assertThat(i.open()).isTrue());
    }

    @Test
    void linkAndHandoffButtons() {
        links.addLink(new Link("demo", "api", "job", Link.Status.SUGGESTED, "once the API is live", "rules", null, "2026-11-02T05:00:00Z"));
        item("k3", List.of("confirm link", "not related"), "job", "link:api>job");
        service.act("demo", "k3", new InboxService.ActionRequest("confirm link", null, null, null));
        assertThat(links.getLink("demo", "api", "job")).get().satisfies(l -> {
            assertThat(l.status()).isEqualTo(Link.Status.CONFIRMED);
            assertThat(l.decidedBy()).isEqualTo("dev");
        });

        item("k4", List.of("accept", "decline"), "faq", "handoff:demo-karan-2026-11-16:faq");
        service.act("demo", "k4", new InboxService.ActionRequest("accept", "nikhil", null, null));
        assertThat(sent).containsExactly("demo-karan-2026-11-16 accept faq nikhil");
    }

    @Test
    void planButtonsBecomeVotesForTheirRound() {
        item("k6", List.of("approve A", "approve B", "reject all"), "api", "negotiation:demo-api-v3:2");
        service.act("demo", "k6", new InboxService.ActionRequest("approve B", "ravi", null, null));
        item("k7", List.of("approve A", "reject all"), "api", "negotiation:demo-api-v3:2");
        service.act("demo", "k7", new InboxService.ActionRequest("reject all", "meera", null, "  the Acme date matters more "));
        assertThat(sent).containsExactly(
                "demo-api-v3 approve r2 B ravi null inbox:k6:approve B",
                "demo-api-v3 reject r2 null meera the Acme date matters more inbox:k7:reject all");
    }

    @Test
    void closedWorkflowsResolveTheItem() {
        item("k5", List.of("kept"), "p1", null);
        closed = true;
        assertThatThrownBy(() -> service.act("demo", "k5", new InboxService.ActionRequest("kept", null, null, null)))
                .isInstanceOf(InboxService.ActionException.class).hasMessageContaining("closed");
        assertThat(inbox.getItem("demo", "k5")).get().satisfies(i -> assertThat(i.resolvedAction()).isEqualTo("closed"));
    }
}
