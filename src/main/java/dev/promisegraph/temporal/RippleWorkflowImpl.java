package dev.promisegraph.temporal;

import dev.promisegraph.links.Ripple;
import dev.promisegraph.temporal.Contracts.RippleRequest;
import dev.promisegraph.temporal.NegotiationContracts.NegotiationRequest;
import dev.promisegraph.temporal.NegotiationContracts.Trigger;
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
                // One negotiation per recorded change: its ID follows this ripple's.
                String rippleId = Workflow.getInfo().getWorkflowId();
                String negotiationId = rippleId.startsWith("ripple-") ? rippleId.substring("ripple-".length()) : rippleId;
                negotiations.startNegotiation(new NegotiationRequest(negotiationId, request, result, trigger));
            }
        }
        return result;
    }
}
