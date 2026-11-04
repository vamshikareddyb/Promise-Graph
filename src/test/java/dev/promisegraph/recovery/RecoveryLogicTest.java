package dev.promisegraph.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import dev.promisegraph.domain.Promise;
import dev.promisegraph.domain.PromiseStatus;
import dev.promisegraph.handoff.Directory;
import dev.promisegraph.links.Ripple;
import dev.promisegraph.sim.Company;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The export API (Neha, platform) slips from 13 Nov to 27 Nov. Dev's ingestion job (analytics, due
 * 18 Nov) waits on it, and Vikram's Acme walkthrough (customer success, due 20 Nov) waits on the job.
 */
class RecoveryLogicTest {

    static final Directory DIRECTORY = new Directory(Company.PEOPLE);

    static Promise promise(String id, String owner, String what, String due) {
        return new Promise(id, "demo", owner, what, PromiseStatus.ACTIVE, 0.9, "2026-11-02T05:00:00Z", "2026-11-02T05:00:00Z",
                null, due, "", List.of(), 1);
    }

    static Situation situation(Map<String, Integer> workload, Set<String> away) {
        Map<String, Promise> ps = new LinkedHashMap<>();
        ps.put("api", promise("api", "neha", "ship the data export API", "2026-11-27"));
        ps.put("job", promise("job", "dev", "build the export ingestion job", "2026-11-18"));
        ps.put("acme", promise("acme", "vikram", "send Acme the usage dashboard walkthrough", "2026-11-20"));
        Map<String, List<String>> deps = Map.of("api", List.of("job"), "job", List.of("acme"));
        Ripple.Result r = Ripple.compute("api", "2026-11-13", "2026-11-27", deps, ps, Ripple.DEFAULT_MAX_DEPTH);
        return new Situation("demo", "api-v3", "2026-11-05", ps.get("api"), "2026-11-13", "2026-11-27", r.impacted(), ps, deps,
                workload, away, DIRECTORY, RecoverySettings.defaults(List.of("Acme", "Globex")));
    }

    static Situation situation() {
        return situation(Map.of("neha", 4, "arjun", 3), Set.of());
    }

    static RecoveryPlanning.Options firstRound(Situation s) {
        return RecoveryPlanning.plan(new RulePlanner(), s, RecoveryPlanning.Feedback.first());
    }

    @Test
    void describesTheSlipAndPicksTheLeadsOfEveryAffectedTeam() {
        Situation s = situation();
        assertThat(s.teamsAffected()).containsExactly("analytics", "customer-success", "platform");
        assertThat(s.customerAtRisk()).isTrue();
        assertThat(s.approvers()).containsExactly("meera", "priya", "ravi");
        assertThat(RecoveryPlanning.describe(s)).startsWith("neha's promise to ship the data export API moved from 2026-11-13 to 2026-11-27")
                .contains("vikram's send Acme the usage dashboard walkthrough (due 2026-11-20, likely 2026-12-04, for a customer)");
    }

    @Test
    void rulesOfferThreeDifferentPlansThatAllPassTheChecks() {
        RecoveryPlanning.Options o = firstRound(situation());
        assertThat(o.plans()).extracting(RecoveryPlan::id).containsExactly("A", "B", "C");
        assertThat(o.plans()).allMatch(RecoveryPlan::passes);
        assertThat(o.plans()).extracting(RecoveryPlan::title)
                .containsExactly("Accept the new dates", "Add arjun to win back time", "Protect the customer dates");

        RecoveryPlan a = o.plans().get(0);
        assertThat(a.moves()).extracting(Move::promiseId, Move::toDue)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("job", "2026-12-02"), org.assertj.core.groups.Tuple.tuple("acme", "2026-12-04"));

        // Arjun (same role, lightest load) wins back 7 of the 14 days; the dependents move 7 days instead of 14.
        RecoveryPlan b = o.plans().get(1);
        assertThat(b.moves()).extracting(Move::describe).containsExactly(
                "arjun helps neha with ship the data export API",
                "neha: ship the data export API 2026-11-27 → 2026-11-20",
                "dev: build the export ingestion job 2026-11-18 → 2026-11-25",
                "vikram: send Acme the usage dashboard walkthrough 2026-11-20 → 2026-11-27");
        assertThat(b.score().peopleAdded()).isEqualTo(1);
        assertThat(b.score().daysLate()).isLessThan(a.score().daysLate());

        RecoveryPlan c = o.plans().get(2);
        assertThat(c.score().customerDatesMoved()).isZero();
        assertThat(c.moves()).anySatisfy(m -> {
            assertThat(m.kind()).isEqualTo(Move.DELIVER_PARTIAL);
            assertThat(m.promiseId()).isEqualTo("acme");
        });
    }

    @Test
    void verifierRejectsPlansThatBreakTheRules() {
        Situation s = situation(Map.of("neha", 4, "arjun", 8), Set.of("sara"));
        Promise api = s.promises().get("api");
        Promise job = s.promises().get("job");
        Promise acme = s.promises().get("acme");

        assertThat(PlanVerifier.verify(RecoveryPlan.draft("Keep everything", "x", List.of(Move.keepDate(job, ""), Move.keepDate(acme, "")), "agent"), s)
                .problems()).containsExactly(
                "\"build the export ingestion job\" (due 2026-11-18) would be due before \"ship the data export API\" (due 2026-11-27), which it waits on");
        assertThat(PlanVerifier.verify(RecoveryPlan.draft("Pull in", "x", List.of(Move.moveDate(api, "2026-11-20", "")), "agent"), s).problems())
                .contains("\"ship the data export API\" comes forward without anyone added to help");
        assertThat(PlanVerifier.verify(RecoveryPlan.draft("Too early", "x",
                List.of(Move.addHelper(api, "kabir", ""), Move.moveDate(api, "2026-11-10", "")), "agent"), s).problems())
                .contains("\"ship the data export API\" would be due before its original date 2026-11-13");
        assertThat(PlanVerifier.verify(RecoveryPlan.draft("Overload", "x", List.of(Move.addHelper(api, "arjun", "")), "agent"), s).problems())
                .contains("arjun would have 9 open promises; the limit is 8");
        assertThat(PlanVerifier.verify(RecoveryPlan.draft("Away", "x", List.of(Move.addHelper(api, "sara", "")), "agent"), s).problems())
                .contains("sara is away before the new date");
        assertThat(PlanVerifier.verify(RecoveryPlan.draft("Ghost", "x", List.of(Move.addHelper(api, "zed", "")), "agent"), s).problems())
                .contains("zed is not in the directory");
        assertThat(PlanVerifier.verify(RecoveryPlan.draft("Past", "x", List.of(Move.moveDate(job, "2026-11-01", "")), "agent"), s).problems())
                .contains("\"build the export ingestion job\" would be due in the past (2026-11-01)");
        Move unknown = new Move(Move.MOVE_DATE, "other", "write the FAQ", "karan", "2026-11-20", "2026-11-30", null, "");
        assertThat(PlanVerifier.verify(RecoveryPlan.draft("Other", "x", List.of(unknown), "agent"), s).problems())
                .contains("\"write the FAQ\" is not one of the promises affected by this slip");
    }

    @Test
    void helpersSkipPeopleWhoAreAwayFullOrAlreadyRejected() {
        Map<String, Integer> load = new HashMap<>(Map.of("arjun", 8));
        Situation s = situation(load, Set.of("sara"));
        assertThat(RulePlanner.rankHelpers(s, List.of())).doesNotContain("arjun", "sara").first().isEqualTo("kabir");
        assertThat(RulePlanner.rankHelpers(situation(), List.of("arjun"))).first().isEqualTo("kabir");
    }

    @Test
    void laterRoundsNeverRepeatRejectedPlansAndSplitsOfferOnlyTheVotedPlans() {
        Situation s = situation();
        RecoveryPlanning.Options first = firstRound(s);
        RecoveryPlanning.Feedback rejected = new RecoveryPlanning.Feedback(2,
                first.plans().stream().map(RecoveryPlan::signature).toList(), List.of("arjun"), List.of(), List.of("meera: too slow"));
        RecoveryPlanning.Options second = RecoveryPlanning.plan(new RulePlanner(), s, rejected);
        assertThat(second.plans()).isNotEmpty().noneMatch(p -> rejected.excludedSignatures().contains(p.signature()));
        assertThat(second.plans()).allSatisfy(p -> assertThat(p.moves()).noneMatch(m -> "arjun".equals(m.person())));

        RecoveryPlanning.Feedback split = new RecoveryPlanning.Feedback(2, List.of(), List.of(), List.of(first.plans().get(1), first.plans().get(2)), List.of());
        RecoveryPlanning.Options again = RecoveryPlanning.plan(new RulePlanner(), s, split);
        assertThat(again.plans()).extracting(RecoveryPlan::title).containsExactly("Add arjun to win back time", "Protect the customer dates");
        assertThat(again.plans()).extracting(RecoveryPlan::id).containsExactly("A", "B");
    }

    @Test
    void customerPromisesAreSpottedByNameOrRecipient() {
        RecoverySettings settings = RecoverySettings.defaults(List.of("Acme"));
        assertThat(settings.customerFacing(promise("x", "vikram", "send Acme the walkthrough", null), DIRECTORY)).isTrue();
        assertThat(settings.customerFacing(promise("x", "vikram", "send the walkthrough to our customers", null), DIRECTORY)).isTrue();
        assertThat(settings.customerFacing(promise("x", "dev", "build the acmesque job", null), DIRECTORY)).isFalse();
        Promise toOutsider = new Promise("x", "demo", "vikram", "send the walkthrough", PromiseStatus.ACTIVE, 0.9, "t", "t",
                "jane@globex.com", null, "", List.of(), 1);
        assertThat(settings.customerFacing(toOutsider, DIRECTORY)).isTrue();
    }
}
