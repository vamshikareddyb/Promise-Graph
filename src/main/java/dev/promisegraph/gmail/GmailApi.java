package dev.promisegraph.gmail;

import java.util.List;

/** The read-only slice of the Gmail API Promise Graph uses. Implemented over REST; faked in tests. */
public interface GmailApi {

    record Profile(String emailAddress, String historyId) {}

    /** One change in the mailbox; Promise Graph only cares which messages were added. */
    record HistoryRecord(String id, List<String> addedMessageIds) {}

    record HistoryPage(List<HistoryRecord> records, String nextPageToken, String historyId) {}

    record MessageList(List<String> ids, String nextPageToken) {}

    Profile profile();

    /**
     * Changes after {@code startHistoryId}.
     *
     * @throws HistoryExpiredException if Gmail no longer has history that old; a full resync is needed
     */
    HistoryPage history(String startHistoryId, String pageToken);

    MessageList listMessages(String query, String pageToken, int maxResults);

    GmailMessage getMessage(String id);

    /** Gmail keeps about a week of history; an older cursor must be replaced by a fresh sync. */
    final class HistoryExpiredException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public HistoryExpiredException(String message) {
            super(message);
        }
    }

    /** A failed call. {@code retryable} tells the activity whether trying again can help. */
    final class GmailApiException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final boolean retryable;

        public GmailApiException(String message, boolean retryable, Throwable cause) {
            super(message, cause);
            this.retryable = retryable;
        }

        public boolean retryable() {
            return retryable;
        }
    }
}
