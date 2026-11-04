package dev.promisegraph.ops;

import java.util.List;

/**
 * What one reconciliation sweep found.
 *
 * @param checked open promises looked at
 * @param restarted promises whose workflow had failed, been terminated or never started, now running again
 * @param outOfSync promises whose workflow finished while the store still shows them open
 */
public record ReconcileResult(int checked, List<String> restarted, List<String> outOfSync) {

    public ReconcileResult {
        restarted = List.copyOf(restarted);
        outOfSync = List.copyOf(outOfSync);
    }
}
