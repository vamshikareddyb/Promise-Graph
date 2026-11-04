package dev.promisegraph.inbox;

import dev.promisegraph.domain.Ids;
import dev.promisegraph.temporal.Contracts.HandoffResponse;
import dev.promisegraph.temporal.Contracts.PromiseSignal;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowNotFoundException;

public final class TemporalSignals implements Signals {

    private final WorkflowClient client;

    public TemporalSignals(WorkflowClient client) {
        this.client = client;
    }

    @Override
    public void toPromise(String promiseId, PromiseSignal signal) {
        try {
            client.newUntypedWorkflowStub(Ids.promiseWorkflowId(promiseId)).signal("event", signal);
        } catch (WorkflowNotFoundException e) {
            throw new ClosedException("that promise is already closed");
        }
    }

    @Override
    public void toHandoff(String handoffId, HandoffResponse response) {
        try {
            client.newUntypedWorkflowStub("handoff-" + handoffId).signal("respond", response);
        } catch (WorkflowNotFoundException e) {
            throw new ClosedException("that handoff has already finished");
        }
    }
}
