package dev.promisegraph.temporal;

import dev.promisegraph.links.Ripple;
import dev.promisegraph.temporal.Contracts.RippleRequest;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/** When a promise slips or is missed: find what depends on it and warn those owners. */
@WorkflowInterface
public interface RippleWorkflow {

    @WorkflowMethod
    Ripple.Result run(RippleRequest request);
}
