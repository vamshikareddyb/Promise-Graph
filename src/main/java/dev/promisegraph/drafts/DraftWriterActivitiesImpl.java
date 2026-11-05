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
            // Retryable: the model is busy, or returned nothing usable this time.
            throw ApplicationFailure.newFailureWithCause(String.valueOf(e.getMessage()), "DraftModelUnavailable", e);
        }
    }
}
