package dev.promisegraph.temporal;

import dev.promisegraph.ops.ReconcileResult;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.time.Instant;

public class ReconcileWorkflowImpl implements ReconcileWorkflow {

    private final OpsActivities ops = Workflow.newActivityStub(OpsActivities.class, RetryPolicies.io(Duration.ofMinutes(10)));

    @Override
    public ReconcileResult run(String tenant) {
        return ops.reconcile(tenant, Instant.ofEpochMilli(Workflow.currentTimeMillis()).toString());
    }
}
