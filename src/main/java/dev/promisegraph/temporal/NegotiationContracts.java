package dev.promisegraph.temporal;

import dev.promisegraph.links.Ripple;
import dev.promisegraph.recovery.Move;
import dev.promisegraph.recovery.RecoveryPlan;
import dev.promisegraph.recovery.RecoveryPlanning;
import dev.promisegraph.temporal.Contracts.RippleRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Inputs and outputs of negotiations and team workflows that cross Temporal boundaries. */
public final class NegotiationContracts {

    private NegotiationContracts() {}

    public static String workflowId(String negotiationId) {
        return "negotiate-" + negotiationId;
    }

    public static String teamWorkflowId(String tenant, String team) {
        return "team-" + tenant + "-" + team;
    }

    /** A helper or new owner held for one promise in one negotiation, whichever plan names them. */
    public static String reservationKey(String negotiationId, String promiseId, String person) {
        return negotiationId + ":" + promiseId + ":" + person;
    }

    public static List<String> reservationKeys(String negotiationId, RecoveryPlan plan) {
        List<String> keys = new ArrayList<>();
        for (Move m : plan.moves()) {
            if (m.addsWork() && m.person() != null) {
                keys.add(reservationKey(negotiationId, m.promiseId(), m.person()));
            }
        }
        return keys;
    }

    /**
     * Whether a ripple should start a negotiation, and with what timing.
     *
     * @param reason why or why not, for the log and the record
     */
    public record Trigger(boolean start, String reason, String sourceOwner, String sourceWhat, int approvalHours, int reminderHours,
            int maxRounds) {

        public static Trigger no(String reason) {
            return new Trigger(false, reason, null, null, 0, 0, 0);
        }
    }

    public record NegotiationRequest(String negotiationId, RippleRequest ripple, Ripple.Result result, Trigger trigger) {}

    /** @param today the workflow's date, so plans never use the past */
    public record ProposeRequest(String negotiationId, RippleRequest ripple, Ripple.Result result, String today,
            RecoveryPlanning.Feedback feedback) {}

    /**
     * An approver's answer, from the inbox or the Plans page. Also used to stop a negotiation.
     *
     * @param kind approve | reject | cancel | superseded
     * @param round the round the answer is for; answers to an earlier round are ignored
     * @param planId for approve: "A", "B" or "C"
     * @param sourceId makes the answer idempotent, e.g. the inbox item and button
     */
    public record Vote(String kind, int round, String planId, String by, String reason, String sourceId) {

        public static Vote stop(String kind, String by, String reason, String sourceId) {
            return new Vote(kind, 0, null, by, reason, sourceId);
        }
    }

    /** @param until reservations end after this date (the late promise's new date) */
    public record ReserveRequest(String tenant, String negotiationId, List<RecoveryPlan> plans, String until) {}

    /** @param refused plan id to the team's reason */
    public record ReserveResult(List<String> accepted, Map<String, String> refused) {}

    /** Releases this negotiation's reservations except {@code keep}. */
    public record ReleaseRequest(String tenant, String negotiationId, List<String> keep) {}

    public record PrecheckRequest(String negotiationId, RippleRequest ripple, Ripple.Result result, String today, RecoveryPlan plan) {}

    /** One move of an approved plan, numbered so its signal and its undo are each sent once. */
    public record ApplyStep(String tenant, String negotiationId, int round, String planId, int index, Move move, String approvedBy) {

        public String sourceId() {
            return "plan:" + negotiationId + ":" + round + ":" + planId + ":" + index;
        }
    }

    // ------------------------------------------------------------ team workflows

    public record Reservation(String key, String person, String negotiationId, String until) {}

    public record TeamInput(String tenant, String team, int capacity, List<Reservation> reservations) {

        public TeamInput {
            reservations = reservations == null ? List.of() : List.copyOf(reservations);
        }
    }

    /** @param load the person's open promises plus reservations, after this answer */
    public record TeamAnswer(boolean ok, String person, int load, int capacity, String reason) {}

    public record TeamSnapshot(String team, int capacity, Map<String, Integer> workload, List<Reservation> reservations, String refreshedAt) {}
}
