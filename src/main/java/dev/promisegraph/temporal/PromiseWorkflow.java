package dev.promisegraph.temporal;

import dev.promisegraph.domain.Promise;
import dev.promisegraph.domain.PromiseStatus;
import dev.promisegraph.temporal.Contracts.PromiseSignal;
import dev.promisegraph.temporal.Contracts.PromiseWorkflowInput;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/** Owns one promise from detection to a final state. */
@WorkflowInterface
public interface PromiseWorkflow {

    @WorkflowMethod
    PromiseStatus run(PromiseWorkflowInput input);

    @SignalMethod
    void event(PromiseSignal signal);

    @QueryMethod
    Promise state();
}
