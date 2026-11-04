package dev.promisegraph.domain;

import static dev.promisegraph.domain.PromiseStatus.ACTIVE;
import static dev.promisegraph.domain.PromiseStatus.AT_RISK;
import static dev.promisegraph.domain.PromiseStatus.BROKEN;
import static dev.promisegraph.domain.PromiseStatus.CANCELLED;
import static dev.promisegraph.domain.PromiseStatus.DETECTED;
import static dev.promisegraph.domain.PromiseStatus.DISMISSED;
import static dev.promisegraph.domain.PromiseStatus.KEPT;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** The promise state machine. Only a promise's own workflow changes its state. */
public final class Lifecycle {

    private static final Map<PromiseStatus, Set<PromiseStatus>> ALLOWED = new EnumMap<>(PromiseStatus.class);

    static {
        ALLOWED.put(DETECTED, EnumSet.of(ACTIVE, DISMISSED, CANCELLED));
        ALLOWED.put(ACTIVE, EnumSet.of(AT_RISK, KEPT, CANCELLED));
        // At risk -> active is a renegotiation: the owner confirmed a new date.
        ALLOWED.put(AT_RISK, EnumSet.of(ACTIVE, KEPT, BROKEN, CANCELLED));
        ALLOWED.put(KEPT, EnumSet.noneOf(PromiseStatus.class));
        ALLOWED.put(BROKEN, EnumSet.noneOf(PromiseStatus.class));
        ALLOWED.put(DISMISSED, EnumSet.noneOf(PromiseStatus.class));
        ALLOWED.put(CANCELLED, EnumSet.noneOf(PromiseStatus.class));
    }

    private Lifecycle() {}

    public static boolean canTransition(PromiseStatus from, PromiseStatus to) {
        return ALLOWED.get(from).contains(to);
    }

    public static PromiseStatus transition(PromiseStatus from, PromiseStatus to) {
        if (!canTransition(from, to)) {
            throw new InvalidTransitionException(from, to);
        }
        return to;
    }

    public static boolean isTerminal(PromiseStatus status) {
        return ALLOWED.get(status).isEmpty();
    }

    public static final class InvalidTransitionException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        public InvalidTransitionException(PromiseStatus from, PromiseStatus to) {
            super("cannot move a promise from " + from + " to " + to);
        }
    }
}
