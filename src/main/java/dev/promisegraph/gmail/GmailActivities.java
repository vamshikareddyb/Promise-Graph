package dev.promisegraph.gmail;

import dev.promisegraph.gmail.GmailSyncContracts.FetchRequest;
import dev.promisegraph.gmail.GmailSyncContracts.FetchResult;
import io.temporal.activity.ActivityInterface;

@ActivityInterface
public interface GmailActivities {

    /** New sent and received emails since the cursor, already filtered and cleaned. Read-only, so safe to retry. */
    FetchResult fetchNewEmails(FetchRequest request);
}
