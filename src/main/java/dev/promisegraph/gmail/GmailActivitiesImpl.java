package dev.promisegraph.gmail;

import dev.promisegraph.domain.Message;
import dev.promisegraph.privacy.Redactor;
import dev.promisegraph.gmail.GmailApi.GmailApiException;
import dev.promisegraph.gmail.GmailApi.HistoryExpiredException;
import dev.promisegraph.gmail.GmailApi.HistoryPage;
import dev.promisegraph.gmail.GmailApi.HistoryRecord;
import dev.promisegraph.gmail.GmailSyncContracts.FetchRequest;
import dev.promisegraph.gmail.GmailSyncContracts.FetchResult;
import io.temporal.failure.ApplicationFailure;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class GmailActivitiesImpl implements GmailActivities {

    private static final Logger log = LoggerFactory.getLogger(GmailActivitiesImpl.class);

    /** Finds the Gmail client for a mailbox; null means the single personal mailbox. */
    private final Function<String, GmailApi> mailboxes;

    public GmailActivitiesImpl(GmailApi gmail) {
        this(mailbox -> gmail);
    }

    public GmailActivitiesImpl(Function<String, GmailApi> mailboxes) {
        this.mailboxes = mailboxes;
    }

    @Override
    public FetchResult fetchNewEmails(FetchRequest req) {
        GmailApi gmail = mailboxes.apply(req.mailbox());
        try {
            if (req.historyId() == null) {
                return backfill(gmail, req, false);
            }
            try {
                return incremental(gmail, req);
            } catch (HistoryExpiredException e) {
                log.warn("Gmail history cursor expired; resyncing the last {} day(s)", req.backfillDays());
                return backfill(gmail, req, true);
            }
        } catch (GmailApiException e) {
            if (e.retryable()) {
                throw ApplicationFailure.newFailureWithCause(e.getMessage(), "GmailUnavailable", e);
            }
            throw ApplicationFailure.newNonRetryableFailureWithCause(e.getMessage(), "GmailAccessDenied", e);
        }
    }

    /** First run, or after the cursor expired: recent mail, then start the cursor from now. */
    private FetchResult backfill(GmailApi gmail, FetchRequest req, boolean resynced) {
        String startCursor = gmail.profile().historyId(); // taken first, so nothing arriving meanwhile is missed
        List<String> ids = new ArrayList<>();
        String pageToken = null;
        do {
            var page = gmail.listMessages("newer_than:" + req.backfillDays() + "d", pageToken, Math.min(100, req.maxMessages()));
            ids.addAll(page.ids());
            pageToken = page.nextPageToken();
        } while (pageToken != null && ids.size() < req.maxMessages());
        List<String> capped = ids.subList(0, Math.min(ids.size(), req.maxMessages()));
        return convert(gmail, req, capped.reversed(), startCursor, false, resynced); // oldest first
    }

    /** Messages added since the cursor, cut at a history-record boundary so none is skipped. */
    private FetchResult incremental(GmailApi gmail, FetchRequest req) {
        Set<String> ids = new LinkedHashSet<>();
        String cursor = req.historyId();
        String pageToken = null;
        boolean hasMore = false;
        outer:
        do {
            HistoryPage page = gmail.history(req.historyId(), pageToken);
            for (HistoryRecord record : page.records()) {
                if (ids.size() >= req.maxMessages()) {
                    hasMore = true;
                    break outer;
                }
                ids.addAll(record.addedMessageIds());
                cursor = record.id();
            }
            pageToken = page.nextPageToken();
            if (pageToken == null) {
                cursor = page.historyId(); // everything read: jump to the mailbox's latest cursor
            }
        } while (pageToken != null);
        return convert(gmail, req, new ArrayList<>(ids), cursor, hasMore, false);
    }

    private FetchResult convert(GmailApi gmail, FetchRequest req, List<String> ids, String nextCursor, boolean hasMore, boolean resynced) {
        List<Message> messages = new ArrayList<>();
        int skipped = 0;
        for (String id : ids) {
            Optional<Message> m;
            try {
                m = GmailMapper.toMessage(gmail.getMessage(id), req.tenant(), req.mailbox());
            } catch (GmailApiException e) {
                if (e.retryable()) {
                    throw e;
                }
                log.warn("skipping Gmail message that could not be read: {}", e.getMessage());
                m = Optional.empty(); // e.g. deleted between the history call and now
            }
            if (m.isPresent()) {
                messages.add(Redactor.redact(m.get())); // before the result enters workflow history
            } else {
                skipped++;
            }
        }
        if (!ids.isEmpty()) {
            log.info("Gmail{}: read {} email(s), {} passed on, {} skipped (promotions, lists, automated or empty)",
                    req.mailbox() == null ? "" : " " + req.mailbox(), ids.size(), messages.size(), skipped);
        }
        return new FetchResult(messages, nextCursor, hasMore, skipped, resynced);
    }
}
