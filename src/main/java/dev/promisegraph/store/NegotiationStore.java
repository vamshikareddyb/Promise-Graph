package dev.promisegraph.store;

import dev.promisegraph.recovery.NegotiationRecord;
import java.util.List;
import java.util.Optional;

/** Snapshots of negotiations for the web app. Each workflow replaces its own snapshot. */
public interface NegotiationStore {

    void save(NegotiationRecord negotiation);

    Optional<NegotiationRecord> get(String tenant, String id);

    /** Newest first. */
    List<NegotiationRecord> list(String tenant);

    /** Negotiations about this promise that are still running. */
    List<NegotiationRecord> openFor(String tenant, String sourcePromiseId);
}
