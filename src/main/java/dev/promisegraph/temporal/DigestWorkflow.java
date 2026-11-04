package dev.promisegraph.temporal;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/** Started each weekday morning by a Temporal Schedule. */
@WorkflowInterface
public interface DigestWorkflow {

    @WorkflowMethod
    int run(String tenant);
}
