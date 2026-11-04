package dev.promisegraph.temporal;

import dev.promisegraph.handoff.HandoffPlanning;
import dev.promisegraph.temporal.Contracts.PlanRequest;
import io.temporal.activity.ActivityInterface;

/** Handoff planning, on the model task queue because the agent planner calls Claude. */
@ActivityInterface
public interface PlanningActivities {

    HandoffPlanning.Plan planHandoff(PlanRequest request);
}
