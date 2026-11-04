package dev.promisegraph.temporal;

import dev.promisegraph.recovery.RecoveryPlanning;
import dev.promisegraph.temporal.NegotiationContracts.ProposeRequest;
import io.temporal.activity.ActivityInterface;

/** Recovery planning, on the model task queue because the option agent calls Claude. */
@ActivityInterface
public interface RecoveryActivities {

    /** Loads the slip fresh, proposes plans, checks them and returns the ones to offer. */
    RecoveryPlanning.Options proposePlans(ProposeRequest request);
}
