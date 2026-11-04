package dev.promisegraph.temporal;

import dev.promisegraph.handoff.HandoffRecord;
import dev.promisegraph.temporal.Contracts.HandoffRequest;
import dev.promisegraph.temporal.Contracts.HandoffResponse;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Covers one person's open promises while they are away, then hands them back.
 *
 * <p>A few days before the absence it plans who should cover each promise and asks them. On the
 * first day away it moves every accepted promise to its cover. The day after the absence ends it
 * moves them back and sends a summary of what changed.
 */
@WorkflowInterface
public interface HandoffWorkflow {

    /** @return the final status */
    @WorkflowMethod
    String run(HandoffRequest request);

    @SignalMethod
    void respond(HandoffResponse response);

    @QueryMethod
    HandoffRecord state();
}
