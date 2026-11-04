package dev.promisegraph.temporal;

import dev.promisegraph.links.Ripple;
import dev.promisegraph.temporal.Contracts.RippleRequest;
import io.temporal.workflow.Workflow;
import java.time.Duration;

public class RippleWorkflowImpl implements RippleWorkflow {

    private final LinkActivities links = Workflow.newActivityStub(LinkActivities.class, RetryPolicies.io(Duration.ofMinutes(1)));

    @Override
    public Ripple.Result run(RippleRequest request) {
        Ripple.Result result = links.computeRipple(request);
        if (!result.impacted().isEmpty() || !result.cycles().isEmpty()) {
            links.applyRipple(request, result);
        }
        return result;
    }
}
