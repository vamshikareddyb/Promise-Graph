package dev.promisegraph.temporal;

import dev.promisegraph.links.Ripple;
import dev.promisegraph.recovery.NegotiationRecord;
import dev.promisegraph.temporal.Contracts.RippleRequest;
import dev.promisegraph.temporal.NegotiationContracts.ApplyStep;
import dev.promisegraph.temporal.NegotiationContracts.NegotiationRequest;
import dev.promisegraph.temporal.NegotiationContracts.PrecheckRequest;
import dev.promisegraph.temporal.NegotiationContracts.ReleaseRequest;
import dev.promisegraph.temporal.NegotiationContracts.ReserveRequest;
import dev.promisegraph.temporal.NegotiationContracts.ReserveResult;
import dev.promisegraph.temporal.NegotiationContracts.Trigger;
import io.temporal.activity.ActivityInterface;
import java.util.List;
import java.util.Map;

@ActivityInterface
public interface NegotiationActivities {

    /** Read-only: does this ripple affect a customer promise or enough teams to negotiate? */
    Trigger shouldNegotiate(RippleRequest request, Ripple.Result result);

    /**
     * Starts the negotiation as its own workflow, not a child of the ripple: it can run for days
     * after the ripple ends. Its ID is fixed per slip, so a retry starts nothing new.
     *
     * @return false if it was already started
     */
    boolean startNegotiation(NegotiationRequest request);

    /** Stops older negotiations about the same promise; a newer slip makes their plans stale. */
    List<String> supersede(String tenant, String sourcePromiseId, String negotiationId);

    void saveNegotiation(NegotiationRecord negotiation);

    /** Asks each team's workflow to hold the people the plans add. Plans a team can't staff are refused. */
    ReserveResult reserve(ReserveRequest request);

    int release(ReleaseRequest request);

    /** Checks the plan again against the promises as they are now. Empty means it still fits. */
    List<String> precheck(PrecheckRequest request);

    /** Applies one move. Fails without retrying if the promise has closed, so the saga undoes earlier moves. */
    void applyMove(ApplyStep step);

    /** Reverses one applied move. Safe to repeat; a promise that has since closed is left alone. */
    void undoMove(ApplyStep step);

    /** Open promises per member of a team, for its team workflow. */
    Map<String, Integer> teamWorkload(String tenant, String team);
}
