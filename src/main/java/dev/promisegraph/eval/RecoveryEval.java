package dev.promisegraph.eval;

import dev.promisegraph.domain.Promise;
import dev.promisegraph.domain.PromiseStatus;
import dev.promisegraph.handoff.Directory;
import dev.promisegraph.links.Ripple;
import dev.promisegraph.recovery.Move;
import dev.promisegraph.recovery.PlanVerifier;
import dev.promisegraph.recovery.RecoveryPlan;
import dev.promisegraph.recovery.RecoveryPlanning;
import dev.promisegraph.recovery.RecoverySettings;
import dev.promisegraph.recovery.RulePlanner;
import dev.promisegraph.recovery.Situation;
import dev.promisegraph.sim.Company;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Gate 3: would leads approve the recovery plans? Run with {@code ./gradlew evalGate3}.
 *
 * <p>Each scenario is a slip in the simulated company plus what its lead would accept, written down
 * before looking at the plans (for example "customer dates must hold" or "don't pull anyone off
 * their work"). The lead approves the first offered plan that meets their rule and otherwise
 * rejects all, which starts the next round, as the negotiation workflow does. The gate also checks
 * that every offered plan names real promises with their real dates and passes the verifier, and
 * that the verifier catches every hand-made bad plan.
 *
 * <p>The leads here are simulated. Passing shows the plans cover sensible choices; it is not proof
 * that real leads would approve them.
 */
public final class RecoveryEval {

    static final double MIN_APPROVAL_RATE = 0.5;
    static final int MAX_ROUNDS = 3;
    static final String TODAY = "2026-11-05";
    static final Directory DIRECTORY = new Directory(Company.PEOPLE);
    static final RecoverySettings SETTINGS = RecoverySettings.defaults(List.of("Acme", "Globex", "Initech"));

    record P(String id, String owner, String what, String due) {}

    /** @param accepts the lead's rule; {@code rule} says it in words */
    record Scenario(String name, List<P> promises, List<List<String>> links, String source, String oldDue, String newDue,
            Map<String, Integer> workload, Set<String> away, String rule, Predicate<RecoveryPlan> accepts) {}

    public record Outcome(String scenario, boolean approved, int round, String plan, List<String> offered) {}

    public record Report(List<Outcome> outcomes, int plansOffered, List<String> integrityProblems, int badCaught, int badTotal) {

        public double approvalRate() {
            return outcomes.isEmpty() ? 0 : (double) outcomes.stream().filter(Outcome::approved).count() / outcomes.size();
        }

        public boolean passed() {
            return approvalRate() >= MIN_APPROVAL_RATE && integrityProblems.isEmpty() && badCaught == badTotal;
        }

        public String render() {
            StringBuilder sb = new StringBuilder();
            for (Outcome o : outcomes) {
                sb.append("%-44s %s%n".formatted(o.scenario(),
                        o.approved() ? "approved plan \"" + o.plan() + "\" in round " + o.round() : "no plan approved in " + MAX_ROUNDS + " rounds"));
            }
            sb.append("approval rate  %.2f (gate: at least %.2f)%n".formatted(approvalRate(), MIN_APPROVAL_RATE));
            sb.append("plans offered  %d, all naming real promises and dates: %s%n".formatted(plansOffered, integrityProblems.isEmpty() ? "yes" : "NO"));
            integrityProblems.forEach(p -> sb.append("  ").append(p).append(System.lineSeparator()));
            sb.append("bad plans      %d of %d caught by the verifier%n".formatted(badCaught, badTotal));
            return sb.toString();
        }
    }

    private RecoveryEval() {}

    // ------------------------------------------------------------ the scenarios

    static boolean noCustomerDateMoves(RecoveryPlan p) {
        return p.score().customerDatesMoved() == 0;
    }

    static boolean addsNobody(RecoveryPlan p) {
        return p.score().peopleAdded() == 0;
    }

    static boolean noPartials(RecoveryPlan p) {
        return p.score().partials() == 0;
    }

    static final List<P> EXPORT_CHAIN = List.of(
            new P("api", "neha", "ship the data export API", "2026-11-13"),
            new P("job", "dev", "build the export ingestion job", "2026-11-18"),
            new P("acme", "vikram", "send Acme the usage dashboard walkthrough", "2026-11-20"));
    static final List<List<String>> EXPORT_LINKS = List.of(List.of("api", "job"), List.of("job", "acme"));

    static List<Scenario> scenarios() {
        List<Scenario> out = new ArrayList<>();
        out.add(new Scenario("customer date must hold", EXPORT_CHAIN, EXPORT_LINKS, "api", "2026-11-13", "2026-11-27",
                Map.of("neha", 4, "arjun", 3), Set.of(), "Acme's date must not move", RecoveryEval::noCustomerDateMoves));
        out.add(new Scenario("win back time, no partial deliveries", EXPORT_CHAIN, EXPORT_LINKS, "api", "2026-11-13", "2026-11-27",
                Map.of("neha", 4, "arjun", 3), Set.of(), "add help rather than split deliveries, total delay under 3 weeks",
                p -> noPartials(p) && p.score().daysLate() < 21));
        out.add(new Scenario("internal slip, nobody pulled in",
                List.of(new P("schema", "meera", "make the schema decision for the export tables", "2026-11-06"),
                        new P("api2", "arjun", "finish the export table migrations", "2026-11-12")),
                List.of(List.of("schema", "api2")), "schema", "2026-11-06", "2026-11-11",
                Map.of(), Set.of(), "don't take anyone off their own work", RecoveryEval::addsNobody));
        out.add(new Scenario("one-day slip",
                List.of(new P("deck", "ravi", "send the pricing deck", "2026-11-10"),
                        new P("brief", "priya", "send the renewal brief to the account team", "2026-11-10")),
                List.of(List.of("deck", "brief")), "deck", "2026-11-10", "2026-11-11",
                Map.of(), Set.of(), "accept a short delay", p -> p.score().daysLate() <= 2));
        out.add(new Scenario("helper needed, best one away", EXPORT_CHAIN, EXPORT_LINKS, "api", "2026-11-13", "2026-11-27",
                Map.of("neha", 4), Set.of("arjun"), "must add someone to the late work",
                p -> p.moves().stream().anyMatch(m -> m.addsWork() && m.promiseId().equals("api"))));
        out.add(new Scenario("team full, wants help", EXPORT_CHAIN, EXPORT_LINKS, "api", "2026-11-13", "2026-11-27",
                Map.of("neha", 8, "arjun", 8, "sara", 8, "kabir", 8, "ravi", 8), Set.of(), "must add someone to the late work",
                p -> p.score().peopleAdded() > 0));
        out.add(new Scenario("customer date holds and help added", EXPORT_CHAIN, EXPORT_LINKS, "api", "2026-11-13", "2026-11-27",
                Map.of("neha", 4, "arjun", 7), Set.of(), "Acme's date holds and someone helps the platform team",
                p -> noCustomerDateMoves(p) && p.score().peopleAdded() > 0));
        out.add(new Scenario("Globex review two steps down",
                List.of(new P("model", "ananya", "retrain the churn model", "2026-11-12"),
                        new P("report", "rahul", "publish the churn report", "2026-11-16"),
                        new P("globex", "leela", "review churn numbers with Globex", "2026-11-19")),
                List.of(List.of("model", "report"), List.of("report", "globex")), "model", "2026-11-12", "2026-11-19",
                Map.of(), Set.of(), "Globex's review must not move", RecoveryEval::noCustomerDateMoves));
        return out;
    }

    static Situation situation(Scenario sc) {
        Map<String, Promise> promises = new LinkedHashMap<>();
        for (P p : sc.promises()) {
            String due = p.id().equals(sc.source()) ? sc.newDue() : p.due();
            promises.put(p.id(), new Promise(p.id(), "eval", p.owner(), p.what(), PromiseStatus.ACTIVE, 0.9,
                    "2026-11-02T05:00:00Z", "2026-11-02T05:00:00Z", null, due, "", List.of(), 1));
        }
        Map<String, List<String>> dependents = new HashMap<>();
        sc.links().forEach(l -> dependents.computeIfAbsent(l.get(0), k -> new ArrayList<>()).add(l.get(1)));
        Ripple.Result r = Ripple.compute(sc.source(), sc.oldDue(), sc.newDue(), dependents, promises, Ripple.DEFAULT_MAX_DEPTH);
        Map<String, Integer> workload = new HashMap<>(sc.workload());
        promises.values().forEach(p -> workload.putIfAbsent(p.owner(), 1));
        return new Situation("eval", sc.name(), TODAY, promises.get(sc.source()), sc.oldDue(), sc.newDue(), r.impacted(), promises,
                dependents, workload, sc.away(), DIRECTORY, SETTINGS);
    }

    /** Does every move name a promise in the slip, with that promise's real owner, text and date? */
    static List<String> integrity(Scenario sc, Situation s, RecoveryPlan plan) {
        List<String> problems = new ArrayList<>();
        if (!PlanVerifier.verify(plan, s).passes()) {
            problems.add(sc.name() + ": offered plan " + plan.id() + " fails the verifier: " + PlanVerifier.verify(plan, s).problems());
        }
        for (Move m : plan.moves()) {
            Promise p = s.promises().get(m.promiseId());
            if (p == null) {
                problems.add(sc.name() + ": plan " + plan.id() + " names unknown promise " + m.promiseId());
                continue;
            }
            if (!p.what().equals(m.what()) || !p.owner().equals(m.owner()) || !java.util.Objects.equals(p.due(), m.fromDue())) {
                problems.add(sc.name() + ": plan " + plan.id() + " misstates " + p.id());
            }
            if (m.changesDate()) {
                try {
                    LocalDate.parse(m.toDue());
                } catch (DateTimeParseException | NullPointerException e) {
                    problems.add(sc.name() + ": plan " + plan.id() + " has an invalid date " + m.toDue());
                }
            }
        }
        return problems;
    }

    /** Plans a careless planner might produce; the verifier must reject every one. */
    static List<RecoveryPlan> badPlans(Situation s) {
        Promise api = s.promises().get("api");
        Promise job = s.promises().get("job");
        Promise acme = s.promises().get("acme");
        return List.of(
                RecoveryPlan.draft("Ignore it", "x", List.of(Move.keepDate(job, ""), Move.keepDate(acme, "")), "test"),
                RecoveryPlan.draft("Wishful", "x", List.of(Move.moveDate(api, "2026-11-15", "")), "test"),
                RecoveryPlan.draft("Too early", "x", List.of(Move.addHelper(api, "arjun", ""), Move.moveDate(api, "2026-11-10", "")), "test"),
                RecoveryPlan.draft("Stranger", "x", List.of(Move.addHelper(api, "someone-new", "")), "test"),
                RecoveryPlan.draft("Past", "x", List.of(Move.moveDate(job, "2026-11-01", "")), "test"),
                RecoveryPlan.draft("Squeeze", "x", List.of(Move.moveDate(job, "2026-11-16", ""), Move.moveDate(acme, "2026-12-04", "")), "test"),
                RecoveryPlan.draft("Empty", "x", List.of(), "test"),
                RecoveryPlan.draft("Self help", "x", List.of(Move.addHelper(api, "neha", "")), "test"),
                RecoveryPlan.draft("Bad date", "x", List.of(new Move(Move.MOVE_DATE, "job", job.what(), job.owner(), job.due(), "next week", null, "")), "test"),
                RecoveryPlan.draft("Outsider", "x", List.of(new Move(Move.MOVE_DATE, "faq", "write the FAQ", "karan", "2026-11-19", "2026-11-30", null, "")), "test"));
    }

    public static Report evaluate(RecoveryPlanning.Planner planner) {
        List<Outcome> outcomes = new ArrayList<>();
        List<String> integrity = new ArrayList<>();
        int offered = 0;
        for (Scenario sc : scenarios()) {
            Situation s = situation(sc);
            List<String> excluded = new ArrayList<>();
            Set<String> helpers = new LinkedHashSet<>();
            Outcome outcome = null;
            for (int round = 1; round <= MAX_ROUNDS && outcome == null; round++) {
                RecoveryPlanning.Options o = RecoveryPlanning.plan(planner, s,
                        new RecoveryPlanning.Feedback(round, excluded, new ArrayList<>(helpers), List.of(), List.of(sc.rule())));
                if (o.plans().isEmpty()) {
                    break;
                }
                offered += o.plans().size();
                for (RecoveryPlan p : o.plans()) {
                    integrity.addAll(integrity(sc, s, p));
                }
                List<String> titles = o.plans().stream().map(RecoveryPlan::title).toList();
                for (RecoveryPlan p : o.plans()) {
                    if (sc.accepts().test(p)) {
                        outcome = new Outcome(sc.name(), true, round, p.title(), titles);
                        break;
                    }
                }
                for (RecoveryPlan p : o.plans()) {
                    excluded.add(p.signature());
                    p.moves().stream().filter(Move::addsWork).forEach(m -> helpers.add(m.person()));
                }
            }
            outcomes.add(outcome != null ? outcome : new Outcome(sc.name(), false, 0, null, List.of()));
        }
        Situation export = situation(scenarios().get(0));
        List<RecoveryPlan> bad = badPlans(export);
        int caught = (int) bad.stream().filter(p -> !PlanVerifier.verify(p, export).passes()).count();
        return new Report(outcomes, offered, integrity, caught, bad.size());
    }

    public static void main(String[] args) {
        Report report = evaluate(new RulePlanner());
        System.out.print(report.render());
        if (!report.passed()) {
            System.out.println("\nGATE 3 FAILED");
            System.exit(1);
        }
        System.out.println("\nGATE 3 PASSED");
    }
}
