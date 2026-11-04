package dev.promisegraph.handoff;

import java.util.ArrayList;
import java.util.List;

/**
 * A handoff as the web app shows it. The handoff workflow writes a new snapshot after every change.
 *
 * @param status scheduled | planning | awaiting_cover | covered | active | returned | cancelled | nothing_to_hand_over
 */
public record HandoffRecord(
        String tenant,
        String id,
        String person,
        String startsOn,
        String endsOn,
        String reason,
        String status,
        String planner,
        List<ItemState> items,
        String updatedAt) {

    public HandoffRecord {
        items = items == null ? List.of() : List.copyOf(items);
    }

    /**
     * One promise in the handoff.
     *
     * @param state proposed | accepted | needs_owner | transferred | returned | uncovered | closed
     */
    public record ItemState(String promiseId, String what, String due, String brief, String proposedOwner,
            String acceptedOwner, String state, String reason) {

        public ItemState withProposal(String owner, String why) {
            return new ItemState(promiseId, what, due, brief, owner, null, owner == null ? "needs_owner" : "proposed", why);
        }

        public ItemState withState(String s) {
            return new ItemState(promiseId, what, due, brief, proposedOwner, acceptedOwner, s, reason);
        }

        public ItemState accepted(String by) {
            return new ItemState(promiseId, what, due, brief, proposedOwner, by, "accepted", reason);
        }

        public ItemState declined(String why) {
            return new ItemState(promiseId, what, due, brief, null, null, "needs_owner", why);
        }
    }

    public HandoffRecord with(String newStatus, List<ItemState> newItems, String planner, String at) {
        return new HandoffRecord(tenant, id, person, startsOn, endsOn, reason, newStatus, planner, new ArrayList<>(newItems), at);
    }
}
