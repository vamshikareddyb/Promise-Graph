package dev.promisegraph.recovery;

import dev.promisegraph.links.Ripple;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** How recovery plans are proposed, checked and narrowed to what approvers see. */
public final class RecoveryPlanning {

    public static final int MAX_PLANS = 3;
    private static final String LABELS = "ABC";

    private RecoveryPlanning() {}

    /**
     * What earlier rounds taught. Round 1 starts empty.
     *
     * @param excludedSignatures plans already rejected; never offered again
     * @param excludedHelpers people already proposed as helpers in rejected plans
     * @param keep when approvers split between plans, the next round offers only these again
     * @param notes approvers' reasons, passed to the agent
     */
    public record Feedback(int round, List<String> excludedSignatures, List<String> excludedHelpers, List<RecoveryPlan> keep,
            List<String> notes) {

        public Feedback {
            excludedSignatures = excludedSignatures == null ? List.of() : List.copyOf(excludedSignatures);
            excludedHelpers = excludedHelpers == null ? List.of() : List.copyOf(excludedHelpers);
            keep = keep == null ? List.of() : List.copyOf(keep);
            notes = notes == null ? List.of() : List.copyOf(notes);
        }

        public static Feedback first() {
            return new Feedback(1, List.of(), List.of(), List.of(), List.of());
        }
    }

    /** Unchecked plans from a planner, and which planner made them. */
    public record Candidates(List<RecoveryPlan> plans, String planner) {

        public Candidates {
            plans = List.copyOf(plans);
        }
    }

    public interface Planner {
        Candidates propose(Situation situation, Feedback feedback);
    }

    /**
     * The plans for one round.
     *
     * @param plans checked plans labelled A, B, C, best first
     * @param dropped plans the verifier rejected, with their problems
     * @param context one paragraph on what slipped and what it affects
     */
    public record Options(List<RecoveryPlan> plans, List<RecoveryPlan> dropped, List<String> approvers, List<String> owners,
            String planner, String context) {

        public Options {
            plans = List.copyOf(plans);
            dropped = List.copyOf(dropped);
            approvers = List.copyOf(approvers);
            owners = List.copyOf(owners);
        }
    }

    /**
     * One round's options: new candidates from the planner, or, after a split vote, the plans that
     * got votes, checked again against the current state.
     */
    public static Options plan(Planner planner, Situation s, Feedback f) {
        if (!f.keep().isEmpty()) {
            List<RecoveryPlan> again = f.keep().stream()
                    .map(p -> RecoveryPlan.draft(p.title(), p.summary(), p.moves(), p.proposedBy())).toList();
            return choose(new Candidates(again, "the plans approvers chose last round"), s, f);
        }
        return choose(planner.propose(s, f), s, f);
    }

    /** Checks every candidate, drops failures, repeats and excluded plans, and labels the rest A, B, C. */
    public static Options choose(Candidates candidates, Situation s, Feedback f) {
        List<RecoveryPlan> valid = new ArrayList<>();
        List<RecoveryPlan> dropped = new ArrayList<>();
        Set<String> seen = new HashSet<>(f.excludedSignatures());
        for (RecoveryPlan c : candidates.plans()) {
            String sig = c.signature();
            if (!seen.add(sig)) {
                continue;
            }
            RecoveryPlan checked = PlanVerifier.verify(c, s);
            if (!checked.passes()) {
                dropped.add(checked);
            } else if (valid.size() < MAX_PLANS) {
                valid.add(checked.withId(String.valueOf(LABELS.charAt(valid.size()))));
            }
        }
        return new Options(valid, dropped, s.approvers(), List.copyOf(s.owners()), candidates.planner(), describe(s));
    }

    public static String describe(Situation s) {
        StringBuilder sb = new StringBuilder();
        sb.append(s.source().owner()).append("'s promise to ").append(s.source().what()).append(" moved from ")
                .append(s.oldDue()).append(" to ").append(s.newDue()).append(". It affects ").append(s.impacts().size())
                .append(s.impacts().size() == 1 ? " promise" : " promises").append(" in ").append(s.teamsAffected().size())
                .append(s.teamsAffected().size() == 1 ? " team" : " teams").append(": ");
        List<String> parts = new ArrayList<>();
        for (Ripple.Impact i : s.impacts()) {
            boolean customer = s.promise(i.promiseId()).map(s::customerFacing).orElse(false);
            parts.add(i.owner() + "'s " + i.what() + " (due " + (i.due() == null ? "no date" : i.due())
                    + (i.likelyDue() == null ? "" : ", likely " + i.likelyDue()) + (customer ? ", for a customer" : "") + ")");
        }
        sb.append(String.join("; ", parts)).append('.');
        return sb.toString();
    }
}
