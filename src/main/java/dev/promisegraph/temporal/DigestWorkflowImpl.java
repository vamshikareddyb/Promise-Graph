package dev.promisegraph.temporal;

import dev.promisegraph.temporal.Contracts.DigestRequest;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.time.Instant;

public class DigestWorkflowImpl implements DigestWorkflow {

    private final PromiseActivities io = Workflow.newActivityStub(PromiseActivities.class, RetryPolicies.io(Duration.ofMinutes(5)));

    @Override
    public int run(String tenant) {
        String now = Instant.ofEpochMilli(Workflow.currentTimeMillis()).toString();
        return io.sendDigests(new DigestRequest(tenant, now, 3));
    }
}
