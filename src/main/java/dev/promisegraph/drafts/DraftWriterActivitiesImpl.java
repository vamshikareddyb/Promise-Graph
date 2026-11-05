package dev.promisegraph.drafts;

import dev.promisegraph.drafts.DraftContracts.DraftContext;
import dev.promisegraph.drafts.DraftContracts.Written;
import io.temporal.failure.ApplicationFailure;

public final class DraftWriterActivitiesImpl implements DraftWriterActivities {

    private final ReplyDrafter drafter;

    public DraftWriterActivitiesImpl(ReplyDrafter drafter) {
        this.drafter = drafter;
    }

    @Override
    public Written writeReply(DraftContext context) {
        try {
            return drafter.write(context);
        } catch (RuntimeException e) {
            String why = String.valueOf(e.getMessage());
            // A bad key, or a model that isn't there, won't fix itself: give up at once rather than after every retry.
            if (why.matches(".*returned 4(0[0-4]|1\\d|2[0-8]|[3-9]\\d).*")) {
                throw ApplicationFailure.newNonRetryableFailureWithCause(why, "DraftModelRejected", e);
            }
            // Retryable: the model is busy, or returned nothing usable this time.
            throw ApplicationFailure.newFailureWithCause(why, "DraftModelUnavailable", e);
        }
    }
}
