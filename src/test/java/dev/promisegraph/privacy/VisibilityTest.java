package dev.promisegraph.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import dev.promisegraph.domain.Promise;
import dev.promisegraph.domain.PromiseStatus;
import dev.promisegraph.handoff.Directory;
import dev.promisegraph.handoff.HandoffRecord;
import dev.promisegraph.handoff.HandoffRecord.ItemState;
import dev.promisegraph.links.Link;
import dev.promisegraph.sim.Company;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Karan (product) is away. Nikhil covers his launch FAQ, so it is Nikhil's now; Karan's roadmap
 * stays with Karan. Dev's ingestion job waits on Neha's export API.
 */
class VisibilityTest {

    static Promise p(String id, String owner, String what) {
        return new Promise(id, "demo", owner, what, PromiseStatus.ACTIVE, 0.9, "t", "t", null, "2026-11-20", "", List.of(), 1);
    }

    final List<Promise> promises = List.of(
            p("faq", "nikhil", "write the launch FAQ for support"),
            p("roadmap", "karan", "share the Q1 roadmap"),
            p("api", "neha", "ship the data export API"),
            p("job", "dev", "build the export ingestion job"),
            p("billing", "arjun", "migrate the billing tables"));
    final List<Link> links = List.of(new Link("demo", "api", "job", Link.Status.CONFIRMED, "once", "rules", "dev", "t"));
    final HandoffRecord handoff = new HandoffRecord("demo", "h1", "karan", "2026-11-16", "2026-11-27", "leave", "active", "rules",
            List.of(new ItemState("faq", "write the launch FAQ for support", "2026-11-20", "brief", "nikhil", "nikhil", "transferred", "same role"),
                    new ItemState("spec", "finish the pricing spec", "2026-11-25", "brief", "zoya", null, "proposed", "same team")),
            "t");
    final Visibility v = new Visibility(new Directory(Company.PEOPLE), List.of("me@example.com"));

    Set<String> sees(String viewer) {
        return v.visibleIds(viewer, promises, links, List.of(), List.of(handoff));
    }

    @Test
    void aTemporaryOwnerSeesOnlyWhatWasHandedToThem() {
        assertThat(sees("nikhil")).containsExactly("faq");
        assertThat(v.view("nikhil", handoff)).get().satisfies(h -> assertThat(h.items()).extracting(ItemState::promiseId).containsExactly("faq"));
        assertThat(v.view("zoya", handoff)).get().satisfies(h -> assertThat(h.items()).extracting(ItemState::promiseId).containsExactly("spec"));
        assertThat(v.view("ritu", handoff)).isEmpty(); // a teammate not asked to cover sees nothing
    }

    @Test
    void ownersLeadsAndLinkedOwnersSeeTheirShare() {
        assertThat(sees("karan")).containsExactlyInAnyOrder("roadmap", "faq", "spec"); // his own, and what he handed over
        assertThat(sees("aditi")).containsExactlyInAnyOrder("faq", "roadmap"); // lead of product
        assertThat(sees("dev")).containsExactlyInAnyOrder("job", "api");       // waits on the API
        assertThat(sees("neha")).containsExactlyInAnyOrder("api", "job");      // the job waits on hers
        assertThat(sees("arjun")).containsExactly("billing");                  // a teammate of Neha's sees only his own
        assertThat(v.view("aditi", handoff)).get().satisfies(h -> assertThat(h.items()).hasSize(2));
    }

    @Test
    void quotesAndOperationsAreNarrower() {
        Promise api = promises.get(2);
        assertThat(v.canSeeQuotes("neha", api)).isTrue();
        assertThat(v.canSeeQuotes("ravi", api)).isFalse(); // her lead sees the promise, not her messages
        assertThat(v.canOperate("ravi")).isTrue();
        assertThat(v.canOperate("neha")).isFalse();
        assertThat(v.canOperate("me@example.com")).isTrue(); // an admin on a personal setup
    }
}
