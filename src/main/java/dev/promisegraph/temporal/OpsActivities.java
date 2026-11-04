package dev.promisegraph.temporal;

import dev.promisegraph.ops.DeadLetter;
import dev.promisegraph.ops.ReconcileResult;
import io.temporal.activity.ActivityInterface;

@ActivityInterface
public interface OpsActivities {

    /** Keeps work that can't finish for a person to retry or dismiss. Repeats are ignored. */
    void recordDeadLetter(DeadLetter deadLetter);

    /**
     * The reconciliation sweep: every open promise must have a running workflow. Workflows that
     * failed, were terminated or never started are restarted from the stored promise; ones that
     * finished while the store still shows them open are reported as dead letters.
     */
    ReconcileResult reconcile(String tenant, String now);
}
