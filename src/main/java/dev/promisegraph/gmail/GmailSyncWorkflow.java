package dev.promisegraph.gmail;

import dev.promisegraph.gmail.GmailSyncContracts.SyncState;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Keeps one Gmail mailbox in sync. Runs indefinitely: fetch new emails, hand them to extraction,
 * advance the cursor, wait, repeat. The cursor lives in workflow state, so a restart resumes exactly
 * where it stopped and no email is processed twice or missed.
 */
@WorkflowInterface
public interface GmailSyncWorkflow {

    @WorkflowMethod
    void run(SyncState state);

    /** Check for new mail now instead of waiting for the next poll (for a future Gmail push notification). */
    @SignalMethod
    void syncNow();

    @QueryMethod
    SyncState state();
}
