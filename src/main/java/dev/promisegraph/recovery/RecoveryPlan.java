package dev.promisegraph.recovery;

import java.util.List;
import java.util.stream.Collectors;

/**
 * A way to recover from a slip: a set of moves that approvers accept or reject as a whole.
 *
 * @param id "A", "B" or "C" within a round; empty until the plans of a round are labelled
 * @param proposedBy rules | agent
 * @param score what the plan costs; null until verified
 * @param problems why the verifier rejects the plan; empty for a plan that may be offered
 */
public record RecoveryPlan(String id, String title, String summary, List<Move> moves, String proposedBy, Score score, List<String> problems) {

    /**
     * @param datesMoved dependent promises that get a later date
     * @param customerDatesMoved of those, promises made to customers
     * @param partials promises kept on date by delivering a first part
     * @param peopleAdded helpers and new owners
     * @param daysLate total days later than originally promised, across the source and its dependents
     */
    public record Score(int datesMoved, int customerDatesMoved, int partials, int peopleAdded, int daysLate) {

        public String describe() {
            return "%d date%s moved (%d for customers), %d first-part deliver%s, %d %s added, %d day%s late in total".formatted(
                    datesMoved, datesMoved == 1 ? "" : "s", customerDatesMoved, partials, partials == 1 ? "y" : "ies",
                    peopleAdded, peopleAdded == 1 ? "person" : "people", daysLate, daysLate == 1 ? "" : "s");
        }
    }

    public RecoveryPlan {
        id = id == null ? "" : id;
        moves = moves == null ? List.of() : List.copyOf(moves);
        problems = problems == null ? List.of() : List.copyOf(problems);
    }

    public static RecoveryPlan draft(String title, String summary, List<Move> moves, String proposedBy) {
        return new RecoveryPlan("", title, summary, moves, proposedBy, null, List.of());
    }

    public RecoveryPlan withId(String newId) {
        return new RecoveryPlan(newId, title, summary, moves, proposedBy, score, problems);
    }

    public RecoveryPlan verified(Score s, List<String> found) {
        return new RecoveryPlan(id, title, summary, moves, proposedBy, s, found);
    }

    /** Not named isValid: Jackson would serialise an is-getter as an extra field. */
    public boolean passes() {
        return score != null && problems.isEmpty();
    }

    /** The same moves in any order give the same signature. */
    public String signature() {
        return moves.stream().map(Move::signature).sorted().collect(Collectors.joining(";"));
    }

    /** One line per plan for notifications. */
    public String headline() {
        return (id.isEmpty() ? "" : id + ") ") + title + ": " + summary;
    }
}
