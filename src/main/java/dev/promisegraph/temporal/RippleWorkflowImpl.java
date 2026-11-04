package dev.promisegraph.temporal;

import static dev.promisegraph.config.PromiseGraphProperties.TASK_QUEUE_MAIN;

import dev.promisegraph.links.Ripple;
import dev.promisegraph.temporal.Contracts.RippleRequest;
import dev.promisegraph.temporal.NegotiationContracts.NegotiationRequest;
import dev.promisegraph.temporal.NegotiationContracts.Trigger;
import io.temporal.api.enums.v1.ParentClosePolicy;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.workflow.Async;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;

public class RippleWorkflowImpl implements RippleWorkflow {

    /** Phase 3 starts a negotiation after a large ripple; histories recorded before it replay without it. */
    static final String NEGOTIATE_CHANGE = "negotiate-after-ripple";

    private final LinkActivities links = Workflow.newActivityStub(LinkActivities.class, RetryPolicies.io(Duration.ofMinutes(1)));
    private final NegotiationActivities negotiations = Workflow.newActivityStub(NegotiationActivities.class, RetryPolicies.io(Duration.ofMinutes(1)));

    @Override
    public Ripple.Result run(RippleRequest request) {
        Ripple.Result result = links.computeRipple(request);
        if (!result.impacted().isEmpty() || !result.cycles().isEmpty()) {
            links.applyRipple(request, result);
        }
        if (Workflow.getVersion(NEGOTIATE_CHANGE, Workflow.DEFAULT_VERSION, 1) == Workflow.DEFAULT_VERSION) {
            return result;
        }
        if (!result.impacted().isEmpty()) {
            Trigger trigger = negotiations.shouldNegotiate(request, result);
            if (trigger.start()) {
                negotiate(request, result, trigger);
            }
        }
        return result;
    }

    /**
     * Starts the negotiation and waits only until it has started; it can take days. Its ID follows
     * this ripple's (one per recorded change), so a repeated ripple can't start a second one.
     */
    private void negotiate(RippleRequest request, Ripple.Result result, Trigger trigger) {
        String rippleId = Workflow.getInfo().getWorkflowId();
        String negotiationId = rippleId.startsWith("ripple-") ? rippleId.substring("ripple-".length()) : rippleId;
        ChildWorkflowOptions options = ChildWorkflowOptions.newBuilder()
                .setWorkflowId(NegotiationContracts.workflowId(negotiationId))
                .setTaskQueue(TASK_QUEUE_MAIN)
                .setParentClosePolicy(ParentClosePolicy.PARENT_CLOSE_POLICY_ABANDON)
                .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                .build();
        NegotiationWorkflow child = Workflow.newChildWorkflowStub(NegotiationWorkflow.class, options);
        Async.function(child::run, new NegotiationRequest(negotiationId, request, result, trigger));
        Workflow.getWorkflowExecution(child).get();
    }
}
