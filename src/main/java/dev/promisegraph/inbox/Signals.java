package dev.promisegraph.inbox;

import dev.promisegraph.temporal.Contracts.HandoffResponse;
import dev.promisegraph.temporal.Contracts.PromiseSignal;
import dev.promisegraph.temporal.NegotiationContracts.Vote;

/** Delivers a person's action to the workflow it is for. Implemented with Temporal; faked in tests. */
public interface Signals {

    /** @throws ClosedException if that workflow has already finished */
    void toPromise(String promiseId, PromiseSignal signal);

    /** @throws ClosedException if that workflow has already finished */
    void toHandoff(String handoffId, HandoffResponse response);

    /** @throws ClosedException if that workflow has already finished */
    void toNegotiation(String negotiationId, Vote vote);

    final class ClosedException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public ClosedException(String message) {
            super(message);
        }
    }
}
