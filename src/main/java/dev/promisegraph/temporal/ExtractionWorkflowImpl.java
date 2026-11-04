package dev.promisegraph.temporal;

import static dev.promisegraph.config.PromiseGraphProperties.TASK_QUEUE_LLM;

import dev.promisegraph.domain.Commitment;
import dev.promisegraph.domain.Message;
import dev.promisegraph.ingestion.ExtractionInput;
import dev.promisegraph.temporal.Contracts.Action;
import dev.promisegraph.temporal.Contracts.ClaimResult;
import dev.promisegraph.temporal.Contracts.DispatchRequest;
import dev.promisegraph.temporal.Contracts.ExtractRequest;
import dev.promisegraph.temporal.Contracts.ExtractionResult;
import dev.promisegraph.temporal.Contracts.ResolveRequest;
import dev.promisegraph.ops.DeadLetter;
import io.temporal.failure.ActivityFailure;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

public class ExtractionWorkflowImpl implements ExtractionWorkflow {

    /** Phase 4 keeps failed batches as dead letters; histories recorded before it replay without it. */
    static final String DEAD_LETTER_CHANGE = "dead-letter-on-extraction-failure";

    private final PromiseActivities io = Workflow.newActivityStub(PromiseActivities.class, RetryPolicies.io(Duration.ofSeconds(30)));
    private final PromiseActivities dispatch = Workflow.newActivityStub(PromiseActivities.class, RetryPolicies.io(Duration.ofMinutes(1)));
    private final ModelActivities model = Workflow.newActivityStub(ModelActivities.class, RetryPolicies.model(TASK_QUEUE_LLM));
    private final OpsActivities ops = Workflow.newActivityStub(OpsActivities.class, RetryPolicies.io(Duration.ofSeconds(30)));
    private final HandoffActivities handoffs = Workflow.newActivityStub(HandoffActivities.class, RetryPolicies.io(Duration.ofSeconds(30)));

    @Override
    public ExtractionResult run(ExtractionInput input) {
        ClaimResult claim = io.claimMessages(input);
        if (!claim.absences().isEmpty()) {
            handoffs.scheduleHandoffs(claim.absences()); // "I'm on leave from 16 Nov" starts a handoff
        }
        List<Message> claimed = claim.candidates();
        if (claimed.isEmpty()) {
            return new ExtractionResult(input.messages().size(), 0, 0, 0);
        }
        List<Commitment> commitments;
        try {
            commitments = model.extractCommitments(new ExtractRequest(input.tenant(), claimed));
        } catch (ActivityFailure e) {
            // The model kept failing. Keep the batch as a dead letter for a person to retry, rather
            // than failing the workflow and losing the claimed messages.
            if (Workflow.getVersion(DEAD_LETTER_CHANGE, Workflow.DEFAULT_VERSION, 1) == Workflow.DEFAULT_VERSION) {
                throw e;
            }
            String id = Workflow.getInfo().getWorkflowId();
            ops.recordDeadLetter(new DeadLetter(input.tenant(), "extraction:" + id, "extraction_failed", id,
                    e.getCause() == null ? e.getMessage() : e.getCause().getMessage(), claimed,
                    Instant.ofEpochMilli(Workflow.currentTimeMillis()).toString(), "open"));
            return new ExtractionResult(input.messages().size(), claimed.size(), 0, 0);
        }
        String now = Instant.ofEpochMilli(Workflow.currentTimeMillis()).toString();
        List<Action> actions = io.resolveCommitments(new ResolveRequest(input.tenant(), commitments, now));
        int dispatched = dispatch.dispatchActions(new DispatchRequest(input.tenant(), actions));
        return new ExtractionResult(input.messages().size(), claimed.size(), commitments.size(), dispatched);
    }
}
