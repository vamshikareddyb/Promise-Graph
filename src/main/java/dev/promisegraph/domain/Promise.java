package dev.promisegraph.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * A tracked commitment. Immutable: changes produce a new value through the {@code with...} methods.
 *
 * @param due ISO date, or null when no date was stated
 * @param version incremented by the promise workflow on every recorded change
 */
public record Promise(
        String id,
        String tenant,
        String owner,
        String what,
        PromiseStatus status,
        double confidence,
        String createdAt,
        String updatedAt,
        String recipient,
        String due,
        String channel,
        List<Evidence> evidence,
        int version) {

    public Promise {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        channel = channel == null ? "" : channel;
    }

    /** Not named isOpen: Jackson would serialise an is-getter as an extra field. */
    public boolean stillOpen() {
        return status.isOpen();
    }

    public Promise withStatus(PromiseStatus s) {
        return new Promise(id, tenant, owner, what, s, confidence, createdAt, updatedAt, recipient, due, channel, evidence, version);
    }

    public Promise withDue(String d) {
        return new Promise(id, tenant, owner, what, status, confidence, createdAt, updatedAt, recipient, d, channel, evidence, version);
    }

    /** Used by handoffs: someone else covers the promise while its owner is away. */
    public Promise withOwner(String o) {
        return new Promise(id, tenant, o, what, status, confidence, createdAt, updatedAt, recipient, due, channel, evidence, version);
    }

    public Promise withWhat(String w) {
        return new Promise(id, tenant, owner, w, status, confidence, createdAt, updatedAt, recipient, due, channel, evidence, version);
    }

    public Promise withVersion(int v, String at) {
        return new Promise(id, tenant, owner, what, status, confidence, createdAt, at, recipient, due, channel, evidence, v);
    }

    /** Adds evidence unless this message is already recorded. */
    public Promise withEvidence(Evidence e) {
        if (evidence.stream().anyMatch(x -> x.messageId().equals(e.messageId()))) {
            return this;
        }
        List<Evidence> next = new ArrayList<>(evidence);
        next.add(e);
        return new Promise(id, tenant, owner, what, status, confidence, createdAt, updatedAt, recipient, due, channel, next, version);
    }
}
