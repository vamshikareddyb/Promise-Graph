package dev.promisegraph.links;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.promisegraph.domain.Message;
import dev.promisegraph.domain.Promise;
import dev.promisegraph.domain.PromiseStatus;
import dev.promisegraph.handoff.Absence;
import dev.promisegraph.handoff.AbsenceDetector;
import dev.promisegraph.handoff.Directory;
import dev.promisegraph.handoff.HandoffPlanning;
import dev.promisegraph.sim.Company;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class LinksAndHandoffLogicTest {

    private static final String NOW = "2026-11-02T05:00:00Z";

    static Promise promise(String id, String owner, String what, String due, PromiseStatus status) {
        return new Promise(id, "demo", owner, what, status, 0.9, NOW, NOW, null, due, "", List.of(), 0);
    }

    @Nested
    class Suggestions {

        private final Promise api = promise("api", "neha", "ship the data export API", "2026-11-13", PromiseStatus.ACTIVE);
        private final Promise deck = promise("deck", "ravi", "send the pricing deck", "2026-11-10", PromiseStatus.ACTIVE);
        private final Promise job = promise("job", "dev", "build the export ingestion job", "2026-11-18", PromiseStatus.DETECTED);

        @Test
        void dependencyPhraseFindsTheUpstreamPromise() {
            var s = LinkSuggester.suggest(job, "I'll build the export ingestion job by 18 Nov, once the API is live.", List.of(api, deck, job));
            assertThat(s).get().satisfies(x -> {
                assertThat(x.upstreamId()).isEqualTo("api");
                assertThat(x.clause()).isEqualTo("the api is live");
            });
        }

        @Test
        void noPhraseOrNoMatchSuggestsNothing() {
            assertThat(LinkSuggester.suggest(job, "I'll build the export ingestion job by 18 Nov.", List.of(api))).isEmpty();
            assertThat(LinkSuggester.suggest(job, "I'll build it once the budget is approved.", List.of(api, deck))).isEmpty();
            assertThat(LinkSuggester.suggest(job, "I'll build it once the API is live.",
                    List.of(api.withStatus(PromiseStatus.KEPT)))).isEmpty(); // closed promises are not candidates
        }
    }

    @Nested
    class Ripples {

        private final Map<String, Promise> ps = Map.of(
                "a", promise("a", "neha", "ship the API", "2026-11-13", PromiseStatus.AT_RISK),
                "b", promise("b", "dev", "build the job", "2026-11-18", PromiseStatus.ACTIVE),
                "c", promise("c", "vikram", "demo to Acme", "2026-11-20", PromiseStatus.ACTIVE),
                "d", promise("d", "leela", "send the report", null, PromiseStatus.ACTIVE),
                "e", promise("e", "omar", "close the ticket", "2026-11-19", PromiseStatus.KEPT));

        @Test
        void keepsEachGapAndFollowsTheChain() {
            var r = Ripple.compute("a", "2026-11-13", "2026-11-27",
                    Map.of("a", List.of("b", "e"), "b", List.of("c", "d")), ps, 5);
            assertThat(r.impacted()).extracting(Ripple.Impact::promiseId).containsExactly("b", "c", "d");
            assertThat(r.impacted().get(0).likelyDue()).isEqualTo("2026-12-02");
            assertThat(r.impacted().get(1).likelyDue()).isEqualTo("2026-12-04");
            assertThat(r.impacted().get(1).via()).isEqualTo("b");
            assertThat(r.impacted().get(2).likelyDue()).isNull(); // no date: "may be delayed"
        }

        @Test
        void earlierDatesAndDepthLimitStopTheRipple() {
            assertThat(Ripple.compute("a", "2026-11-13", "2026-11-10", Map.of("a", List.of("b")), ps, 5).impacted()).isEmpty();
            assertThat(Ripple.compute("a", "2026-11-13", "2026-11-27", Map.of("a", List.of("b"), "b", List.of("c")), ps, 1).impacted())
                    .extracting(Ripple.Impact::promiseId).containsExactly("b");
        }

        @Test
        void missedPromiseFlagsDirectDependentsWithoutDates() {
            var r = Ripple.compute("a", "2026-11-13", null, Map.of("a", List.of("b")), ps, 5);
            assertThat(r.impacted()).singleElement().satisfies(i -> assertThat(i.likelyDue()).isNull());
        }

        @Test
        void findsLoopsOnce() {
            var r = Ripple.compute("a", "2026-11-13", "2026-11-27",
                    Map.of("a", List.of("b"), "b", List.of("c"), "c", List.of("a")), ps, 5);
            assertThat(r.cycles()).containsExactly(List.of("a", "b", "c", "a"));
        }
    }

    @Nested
    class Absences {

        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = '|', value = {
                "Heads up, I'm on leave from 16 Nov to 27 Nov. | 2026-11-16 | 2026-11-27",
                "I'm OOO tomorrow.                             | 2026-11-03 | 2026-11-03",
                "I'll be out until Friday.                     | 2026-11-02 | 2026-11-06",
                "I'm on leave next week.                       | 2026-11-09 | 2026-11-13",
                "Are you on leave tomorrow?                    |            |",
                "Ravi is on leave next week.                   |            |",
                "I'll be out for a bit.                        |            |"})
        void detectsOwnPlannedAbsences(String text, String start, String end) {
            var found = AbsenceDetector.detect(new Message("m", "c", "me", "2026-11-02T10:00:00+05:30", text, "demo"));
            if (start == null) {
                assertThat(found).isEmpty();
            } else {
                assertThat(found).get().satisfies(a -> {
                    assertThat(a.startsOn()).isEqualTo(start);
                    assertThat(a.endsOn()).isEqualTo(end);
                    assertThat(a.person()).isEqualTo("me");
                });
            }
        }

        @Test
        void validatesAndOverlaps() {
            assertThatThrownBy(() -> new Absence("demo", "x", "2026-11-10", "2026-11-01", "leave", "manual"))
                    .isInstanceOf(IllegalArgumentException.class);
            Absence a = new Absence("demo", "omar", "2026-11-23", "2026-11-24", "leave", "manual");
            assertThat(a.overlaps("2026-11-16", "2026-11-27")).isTrue();
            assertThat(a.overlaps("2026-11-25", null)).isFalse();
            assertThat(a.handoffId()).isEqualTo("demo-omar-2026-11-23");
        }
    }

    @Nested
    class Planning {

        private final Directory directory = new Directory(Company.PEOPLE);
        private final Promise faq = promise("faq", "karan", "write the launch FAQ for support", "2026-11-19", PromiseStatus.ACTIVE);

        private HandoffPlanning.Context ctx(Set<String> unavailable, Map<String, Integer> workload, Map<String, List<Promise>> blocks) {
            return new HandoffPlanning.Context("h1", "karan", "2026-11-16", "2026-11-27", List.of(faq), Map.of(), blocks,
                    workload, unavailable, directory);
        }

        @Test
        void prefersSameRoleAndTellsTheLead() {
            var plan = new HandoffPlanning.RulePlanner().plan(ctx(Set.of(), Map.of(), Map.of()));
            assertThat(plan.items()).singleElement().satisfies(i -> {
                assertThat(i.proposedOwner()).isEqualTo("nikhil");
                assertThat(i.reason()).contains("same role");
                assertThat(i.brief()).startsWith("write the launch FAQ for support. Due 2026-11-19.");
            });
            assertThat(plan.decider()).isEqualTo("aditi");
        }

        @Test
        void skipsPeopleAwayAndWeighsWorkloadAndLinks() {
            assertThat(new HandoffPlanning.RulePlanner().plan(ctx(Set.of("nikhil"), Map.of(), Map.of())).items().get(0).proposedOwner())
                    .isNotEqualTo("nikhil");
            Promise omarWaits = promise("t", "omar", "train support on the launch", "2026-11-21", PromiseStatus.ACTIVE);
            var linked = new HandoffPlanning.RulePlanner().plan(ctx(Set.of(), Map.of("nikhil", 8), Map.of("faq", List.of(omarWaits))));
            assertThat(linked.items().get(0).proposedOwner()).isEqualTo("omar"); // works on a linked promise, nikhil is busy
        }

        @Test
        void outsideTheDirectoryNobodyIsProposedAndThePersonDecides() {
            var ctx = new HandoffPlanning.Context("h2", "someone@gmail.com", "2026-11-16", "2026-11-20", List.of(faq),
                    Map.of(), Map.of(), Map.of(), Set.of(), directory);
            var plan = new HandoffPlanning.RulePlanner().plan(ctx);
            assertThat(plan.items().get(0).proposedOwner()).isNull();
            assertThat(plan.decider()).isEqualTo("someone@gmail.com");
        }
    }
}
