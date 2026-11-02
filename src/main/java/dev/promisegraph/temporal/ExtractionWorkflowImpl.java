package dev.promisegraph.temporal;

import static dev.promisegraph.config.PromiseGraphProperties.TASK_QUEUE_LLM;

import dev.promisegraph.domain.Commitment;
import dev.promisegraph.domain.Message;
import dev.promisegraph.ingestion.ExtractionInput;
import dev.promisegraph.temporal.Contracts.Action;
import dev.promisegraph.temporal.Contracts.DispatchRequest;
import dev.promisegraph.temporal.Contracts.ExtractRequest;
import dev.promisegraph.temporal.Contracts.ExtractionResult;
import dev.promisegraph.temporal.Contracts.ResolveRequest;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

public class ExtractionWorkflowImpl implements ExtractionWorkflow {

    private final PromiseActivities io = Workflow.newActivityStub(PromiseActivities.class, RetryPolicies.io(Duration.ofSeconds(30)));
    private final PromiseActivities dispatch = Workflow.newActivityStub(PromiseActivities.class, RetryPolicies.io(Duration.ofMinutes(1)));
    private final ModelActivities model = Workflow.newActivityStub(ModelActivities.class, RetryPolicies.model(TASK_QUEUE_LLM));

    @Override
    public ExtractionResult run(ExtractionInput input) {
        List<Message> claimed = io.claimMessages(input);
        if (claimed.isEmpty()) {
            return new ExtractionResult(input.messages().size(), 0, 0, 0);
        }
        List<Commitment> commitments = model.extractCommitments(new ExtractRequest(input.tenant(), claimed));
        String now = Instant.ofEpochMilli(Workflow.currentTimeMillis()).toString();
        List<Action> actions = io.resolveCommitments(new ResolveRequest(input.tenant(), commitments, now));
        int dispatched = dispatch.dispatchActions(new DispatchRequest(input.tenant(), actions));
        return new ExtractionResult(input.messages().size(), claimed.size(), commitments.size(), dispatched);
    }
}
