package dev.promisegraph.temporal;

import dev.promisegraph.temporal.NegotiationContracts.ReleaseRequest;
import dev.promisegraph.temporal.NegotiationContracts.Reservation;
import dev.promisegraph.temporal.NegotiationContracts.TeamAnswer;
import dev.promisegraph.temporal.NegotiationContracts.TeamInput;
import dev.promisegraph.temporal.NegotiationContracts.TeamSnapshot;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.UpdateMethod;
import io.temporal.workflow.UpdateValidatorMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * One long-running workflow per team. It holds the team's workload, refreshed daily, and the
 * people that open negotiations have asked for. Negotiations ask it through updates, so two plans
 * running at once can't both count on the same free person, and every answer is in its history.
 */
@WorkflowInterface
public interface TeamWorkflow {

    @WorkflowMethod
    void run(TeamInput input);

    /** Holds one person for one negotiation if it keeps them within the workload limit. Repeats get the same answer. */
    @UpdateMethod
    TeamAnswer reserve(Reservation reservation);

    @UpdateValidatorMethod(updateName = "reserve")
    void validateReserve(Reservation reservation);

    /** @return how many reservations were released */
    @UpdateMethod
    int release(ReleaseRequest request);

    @QueryMethod
    TeamSnapshot snapshot();
}
