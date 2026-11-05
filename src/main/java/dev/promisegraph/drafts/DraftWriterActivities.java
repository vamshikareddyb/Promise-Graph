package dev.promisegraph.drafts;

import dev.promisegraph.drafts.DraftContracts.DraftContext;
import dev.promisegraph.drafts.DraftContracts.Written;
import io.temporal.activity.ActivityInterface;

/** The model call, on the model task queue with its concurrency cap. */
@ActivityInterface
public interface DraftWriterActivities {

    Written writeReply(DraftContext context);
}
