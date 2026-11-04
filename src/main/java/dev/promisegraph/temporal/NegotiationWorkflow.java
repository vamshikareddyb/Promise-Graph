package dev.promisegraph.temporal;

import dev.promisegraph.recovery.NegotiationRecord;
import dev.promisegraph.temporal.NegotiationContracts.NegotiationRequest;
import dev.promisegraph.temporal.NegotiationContracts.Vote;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * When a slip puts other teams or a customer at risk: propose recovery plans, wait for the leads
 * to approve one, and apply it, undoing everything if a step fails.
 */
@WorkflowInterface
public interface NegotiationWorkflow {

    /** @return the final status, e.g. applied, expired or unresolved */
    @WorkflowMethod
    String run(NegotiationRequest request);

    @SignalMethod(name = "vote")
    void vote(Vote vote);

    @QueryMethod
    NegotiationRecord state();
}
