package dev.promisegraph.recovery;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The web app's view of one negotiation, saved by its workflow after every change.
 *
 * @param id {@code <promiseId>-v<version>}: one negotiation per recorded slip
 * @param status planning | awaiting_approval | applying | applied | failed | expired | unresolved
 *     | superseded | cancelled | not_needed
 * @param plans the plans offered in the current round
 * @param dropped plans the verifier or the teams rejected this round, with the reasons
 * @param votes approver to "A", "B", "C" or "reject" for the current round
 * @param chosen the approved plan, once there is one
 * @param deadline when the current round's approval wait ends
 * @param history one line per step, oldest first
 */
public record NegotiationRecord(
        String tenant,
        String id,
        String sourcePromiseId,
        String sourceWhat,
        String sourceOwner,
        String oldDue,
        String newDue,
        String status,
        int round,
        String context,
        String planner,
        List<RecoveryPlan> plans,
        List<RecoveryPlan> dropped,
        List<String> approvers,
        Map<String, String> votes,
        String chosen,
        String deadline,
        List<String> history,
        String createdAt,
        String updatedAt) {

    public static final List<String> OPEN = List.of("planning", "awaiting_approval", "applying");

    public NegotiationRecord {
        plans = plans == null ? List.of() : List.copyOf(plans);
        dropped = dropped == null ? List.of() : List.copyOf(dropped);
        approvers = approvers == null ? List.of() : List.copyOf(approvers);
        votes = votes == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(votes));
        history = history == null ? List.of() : List.copyOf(history);
    }

    /** Not named isOpen: Jackson would serialise an is-getter as an extra field. */
    public boolean stillOpen() {
        return OPEN.contains(status);
    }

    public Optional<RecoveryPlan> plan(String planId) {
        return plans.stream().filter(p -> p.id().equals(planId)).findFirst();
    }
}
