package dev.promisegraph.handoff;

import dev.promisegraph.config.PromiseGraphProperties;
import dev.promisegraph.store.HandoffStore;
import dev.promisegraph.temporal.Contracts.HandoffRequest;
import dev.promisegraph.temporal.HandoffWorkflow;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import java.time.Clock;
import java.time.LocalDate;

/** Records an absence and starts its handoff workflow. Used by message extraction and the web app. */
public final class HandoffScheduler {

    private final HandoffStore store;
    private final WorkflowClient client;
    private final int prepDays;
    private final Clock clock;

    public HandoffScheduler(HandoffStore store, WorkflowClient client, int prepDays, Clock clock) {
        this.store = store;
        this.client = client;
        this.prepDays = prepDays;
        this.clock = clock;
    }

    public static String workflowId(Absence a) {
        return "handoff-" + a.handoffId();
    }

    /** @return true if a new handoff was started; false for a past absence or one already scheduled */
    public boolean schedule(Absence a) {
        if (a.endsOn() != null && LocalDate.parse(a.endsOn()).isBefore(LocalDate.now(clock))) {
            return false; // already over, e.g. an old email read during the first sync
        }
        store.addAbsence(a);
        WorkflowOptions options = WorkflowOptions.newBuilder()
                .setWorkflowId(workflowId(a))
                .setTaskQueue(PromiseGraphProperties.TASK_QUEUE_MAIN)
                .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                .build();
        try {
            WorkflowClient.start(client.newWorkflowStub(HandoffWorkflow.class, options)::run, new HandoffRequest(a, prepDays));
            return true;
        } catch (WorkflowExecutionAlreadyStarted e) {
            return false;
        }
    }
}
