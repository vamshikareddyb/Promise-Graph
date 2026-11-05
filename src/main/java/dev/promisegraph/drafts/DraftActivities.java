package dev.promisegraph.drafts;

import dev.promisegraph.drafts.DraftContracts.DraftRequest;
import dev.promisegraph.drafts.DraftContracts.Prepared;
import io.temporal.activity.ActivityInterface;
import java.util.List;

/** The reply-draft agent's I/O, on the main task queue. Every method is safe to retry. */
@ActivityInterface
public interface DraftActivities {

    /** Starts one draft workflow per email; one already started for an email is left alone. */
    void startDrafts(List<DraftRequest> requests);

    /**
     * Gathers what the drafter needs: the Gmail thread and the promises tracked around it. Skips
     * emails the person wrote, emails already answered, and emails already drafted (unless replacing).
     */
    Prepared prepareDraft(DraftRequest request);

    /** @param replace overwrite an existing draft (regenerating); otherwise keep it */
    void saveDraft(ReplyDraft draft, boolean replace);
}
