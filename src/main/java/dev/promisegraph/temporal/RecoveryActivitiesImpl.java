package dev.promisegraph.temporal;

import dev.promisegraph.recovery.RecoveryPlanning;
import dev.promisegraph.recovery.Situation;
import dev.promisegraph.temporal.NegotiationContracts.ProposeRequest;
import java.util.List;
import java.util.Optional;

/** Loads the slip fresh and runs the configured planner (rules or the option agent). */
public final class RecoveryActivitiesImpl implements RecoveryActivities {

    private final RecoveryPlanning.Planner planner;
    private final SituationLoader loader;

    public RecoveryActivitiesImpl(RecoveryPlanning.Planner planner, SituationLoader loader) {
        this.planner = planner;
        this.loader = loader;
    }

    @Override
    public RecoveryPlanning.Options proposePlans(ProposeRequest req) {
        Optional<Situation> s = loader.load(req.negotiationId(), req.ripple(), req.result(), req.today());
        if (s.isEmpty()) {
            return new RecoveryPlanning.Options(List.of(), List.of(), List.of(), List.of(), "none", "The late promise no longer exists.");
        }
        return RecoveryPlanning.plan(planner, s.get(), req.feedback());
    }
}
