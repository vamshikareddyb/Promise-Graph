package dev.promisegraph.temporal;

import dev.promisegraph.links.Ripple;
import dev.promisegraph.temporal.Contracts.RippleRequest;
import io.temporal.activity.ActivityInterface;

@ActivityInterface
public interface LinkActivities {

    /** Walks confirmed links from the changed promise. Read-only. */
    Ripple.Result computeRipple(RippleRequest request);

    /**
     * Tells every affected promise (a signal each) and the people involved (a notification each).
     * Signals and notifications are keyed by the change, so a retry repeats nothing.
     *
     * @return how many promises were told
     */
    int applyRipple(RippleRequest request, Ripple.Result result);
}
