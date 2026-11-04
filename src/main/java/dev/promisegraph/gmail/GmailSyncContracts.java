package dev.promisegraph.gmail;

import dev.promisegraph.domain.Message;
import java.util.List;

/** Inputs and outputs of the Gmail sync workflow and its activity. */
public final class GmailSyncContracts {

    private GmailSyncContracts() {}

    /**
     * The workflow's durable state, carried across continue-as-new.
     *
     * @param historyId Gmail's change cursor; null before the first sync
     */
    public record SyncState(String tenant, String historyId, int pollSeconds, int backfillDays, long processed) {

        public SyncState withProgress(String nextHistoryId, int count) {
            return new SyncState(tenant, nextHistoryId, pollSeconds, backfillDays, processed + count);
        }
    }

    public record FetchRequest(String tenant, String historyId, int backfillDays, int maxMessages) {}

    /**
     * @param nextHistoryId where the next fetch starts
     * @param hasMore true when more changes are waiting and the workflow should fetch again at once
     * @param skipped emails read but not passed on (spam, promotions, mailing lists, no text)
     */
    public record FetchResult(List<Message> messages, String nextHistoryId, boolean hasMore, int skipped, boolean resynced) {}
}
