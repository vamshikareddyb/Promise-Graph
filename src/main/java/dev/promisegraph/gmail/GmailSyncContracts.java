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
     * @param mailbox the account read, in team mode; null for the single personal mailbox
     */
    public record SyncState(String tenant, String historyId, int pollSeconds, int backfillDays, long processed, String mailbox) {

        public static SyncState personal(String tenant, int pollSeconds, int backfillDays) {
            return new SyncState(tenant, null, pollSeconds, backfillDays, 0, null);
        }

        public static SyncState forAccount(String tenant, String mailbox, int pollSeconds, int backfillDays) {
            return new SyncState(tenant, null, pollSeconds, backfillDays, 0, mailbox);
        }

        public SyncState withProgress(String nextHistoryId, int count) {
            return new SyncState(tenant, nextHistoryId, pollSeconds, backfillDays, processed + count, mailbox);
        }
    }

    /** @param mailbox as in {@link SyncState}; null for the personal mailbox */
    public record FetchRequest(String tenant, String historyId, int backfillDays, int maxMessages, String mailbox) {}

    /** One sync workflow per mailbox; the personal one keeps its original ID. */
    public static String workflowId(String tenant, String mailbox) {
        return mailbox == null ? "gmail-sync-" + tenant : "gmail-sync-" + tenant + "-" + mailbox.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /**
     * @param nextHistoryId where the next fetch starts
     * @param hasMore true when more changes are waiting and the workflow should fetch again at once
     * @param skipped emails read but not passed on (spam, promotions, mailing lists, no text)
     */
    public record FetchResult(List<Message> messages, String nextHistoryId, boolean hasMore, int skipped, boolean resynced) {}
}
