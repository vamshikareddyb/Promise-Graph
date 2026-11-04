package dev.promisegraph.temporal;

import dev.promisegraph.ops.ReconcileResult;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/** Run hourly by a schedule: makes sure every open promise has a running workflow. */
@WorkflowInterface
public interface ReconcileWorkflow {

    @WorkflowMethod
    ReconcileResult run(String tenant);
}
