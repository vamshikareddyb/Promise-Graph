package dev.promisegraph.gmail;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.promisegraph.domain.Message;
import dev.promisegraph.gmail.GmailApi.HistoryExpiredException;
import dev.promisegraph.gmail.GmailApi.HistoryPage;
import dev.promisegraph.gmail.GmailApi.HistoryRecord;
import dev.promisegraph.gmail.GmailApi.MessageList;
import dev.promisegraph.gmail.GmailApi.Profile;
import dev.promisegraph.gmail.GmailSyncContracts.FetchRequest;
import dev.promisegraph.gmail.GmailSyncContracts.FetchResult;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class GmailTest {

    static GmailMessage email(String id, String from, String body, String... labels) {
        return new GmailMessage(id, "t-" + id, List.of(labels), 1_793_607_000_000L, Map.of("From", from, "Subject", "Re: export"), body, null);
    }

    @Nested
    class Cleaning {

        @Test
        void keepsOnlyTheSendersOwnWords() {
            String body = """
                    Hi Ravi,
                    I'll send the export spec by Friday.

                    Thanks,
                    Neha
                    --
                    Neha Kapoor | Backend engineer

                    On Mon, 2 Nov 2026 at 10:00, Ravi <ravi@example.com> wrote:
                    > Can you send the spec? I'll review it by Monday.
                    """;
            String clean = EmailText.clean(body, null);
            assertThat(clean).contains("I'll send the export spec by Friday.").doesNotContain("review it by Monday", "Backend engineer");
        }

        @Test
        void stopsAtOutlookAndForwardedHistory() {
            assertThat(EmailText.clean("Done: sent the deck.\n\nFrom: Ravi <ravi@example.com>\nI'll call tomorrow.", null))
                    .isEqualTo("Done: sent the deck.");
            assertThat(EmailText.clean("FYI\n---------- Forwarded message ---------\nI'll ship it Friday.", null)).isEqualTo("FYI");
            assertThat(EmailText.clean("I'll do it today.\nSent from my iPhone", null)).isEqualTo("I'll do it today.");
        }

        @Test
        void readsHtmlWhenThereIsNoPlainText() {
            String html = "<html><head><style>p{}</style></head><body><p>I&#39;ll send it by Friday.</p>"
                    + "<div class=\"gmail_quote\">On Mon wrote: I'll review it</div></body></html>";
            assertThat(EmailText.clean(null, html)).isEqualTo("I'll send it by Friday.");
        }

        @Test
        void capsLength() {
            assertThat(EmailText.clean("a".repeat(10_000), null)).hasSize(EmailText.MAX_LENGTH);
        }
    }

    @Nested
    class Mapping {

        @Test
        void mapsSentAndReceivedMail() {
            Message m = GmailMapper.toMessage(email("abc", "Neha Kapoor <Neha@Example.com>", "I'll send it by Friday.", "INBOX"), "demo").orElseThrow();
            assertThat(m.id()).isEqualTo("gmail-abc");
            assertThat(m.sender()).isEqualTo("neha@example.com");
            assertThat(m.channel()).isEqualTo("email:t-abc");
            assertThat(m.ts()).isEqualTo("2026-11-02T08:10Z");
            assertThat(GmailMapper.toMessage(email("s1", "me@example.com", "I'll call you tomorrow.", "SENT"), "demo")).isPresent();
        }

        @Test
        void skipsSpamPromotionsListsAndAutomatedSenders() {
            assertThat(GmailMapper.toMessage(email("1", "a@x.com", "We'll ship by Friday!", "CATEGORY_PROMOTIONS"), "demo")).isEmpty();
            assertThat(GmailMapper.toMessage(email("2", "a@x.com", "I'll do it.", "SPAM"), "demo")).isEmpty();
            assertThat(GmailMapper.toMessage(email("3", "noreply@shop.com", "We'll deliver tomorrow.", "INBOX"), "demo")).isEmpty();
            GmailMessage list = new GmailMessage("4", "t", List.of("INBOX"), 0,
                    Map.of("From", "news@x.com", "List-Unsubscribe", "<mailto:u@x.com>"), "We'll be in touch.", null);
            assertThat(GmailMapper.toMessage(list, "demo")).isEmpty();
            assertThat(GmailMapper.toMessage(email("5", "a@x.com", "> only quoted text", "INBOX"), "demo")).isEmpty();
        }
    }

    @Test
    void parsesNestedMimeParts() throws Exception {
        String plain = Base64.getUrlEncoder().withoutPadding().encodeToString("I'll send it by Friday.".getBytes(StandardCharsets.UTF_8));
        String json = """
                {"id":"m1","threadId":"t1","labelIds":["INBOX"],"internalDate":"1793607000000",
                 "payload":{"mimeType":"multipart/mixed","headers":[{"name":"From","value":"a@x.com"}],
                   "parts":[{"mimeType":"multipart/alternative","parts":[
                     {"mimeType":"text/plain","filename":"","body":{"data":"%s"}},
                     {"mimeType":"text/html","filename":"","body":{"data":"PGI-aGk8L2I-"}}]},
                     {"mimeType":"text/plain","filename":"notes.txt","body":{"attachmentId":"x"}}]}}
                """.formatted(plain);
        GmailMessage m = GmailRestApi.parseMessage(new ObjectMapper().readTree(json));
        assertThat(m.plainText()).isEqualTo("I'll send it by Friday.");
        assertThat(m.html()).isEqualTo("<b>hi</b>");
        assertThat(m.header("from")).isEqualTo("a@x.com");
        assertThat(m.internalDate()).isEqualTo(1_793_607_000_000L);
    }

    @Test
    void pkceAndAuthUrl() {
        // Expected value computed independently with Python's hashlib.
        assertThat(GmailAuthorize.challenge("dBjftJeZ4CVP-mJ92K9-6mL2mXzrGFKyYOBYjf2aeVE"))
                .isEqualTo("V-wpL4e6VUvcZBnhpF3wMEQ43aVXpvOSSICwTlPvTKg");
        String url = GmailAuthorize.authUrl("client-1", "http://127.0.0.1:5000", "st", "ch");
        assertThat(url).contains("scope=https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fgmail.readonly",
                "access_type=offline", "code_challenge_method=S256", "state=st");
        assertThat(GmailAuthorize.parseQuery("code=4%2Fabc&state=st")).containsEntry("code", "4/abc").containsEntry("state", "st");
    }

    /** A scripted mailbox. */
    static final class FakeGmail implements GmailApi {
        final Map<String, GmailMessage> messages = new LinkedHashMap<>();
        final List<HistoryRecord> history = new ArrayList<>();
        String latestHistoryId = "500";
        boolean expired;

        @Override
        public Profile profile() {
            return new Profile("me@example.com", latestHistoryId);
        }

        @Override
        public HistoryPage history(String startHistoryId, String pageToken) {
            if (expired) {
                throw new HistoryExpiredException("too old");
            }
            long start = Long.parseLong(startHistoryId);
            List<HistoryRecord> after = history.stream().filter(h -> Long.parseLong(h.id()) > start).toList();
            int from = pageToken == null ? 0 : Integer.parseInt(pageToken);
            int to = Math.min(after.size(), from + 2); // two records per page, to exercise paging
            return new HistoryPage(after.subList(from, to), to < after.size() ? String.valueOf(to) : null, latestHistoryId);
        }

        @Override
        public MessageList listMessages(String query, String pageToken, int maxResults) {
            return new MessageList(new ArrayList<>(messages.keySet()).reversed(), null); // Gmail lists newest first
        }

        @Override
        public GmailMessage getMessage(String id) {
            return messages.get(id);
        }
    }

    @Nested
    class Fetching {

        @Test
        void firstRunReadsRecentMailOldestFirstAndStartsTheCursor() {
            FakeGmail gmail = new FakeGmail();
            gmail.messages.put("a", email("a", "x@y.com", "I'll send the deck by Friday.", "INBOX"));
            gmail.messages.put("b", email("b", "x@y.com", "Big sale!", "CATEGORY_PROMOTIONS"));
            gmail.messages.put("c", email("c", "me@example.com", "Done: sent the deck.", "SENT"));

            FetchResult r = new GmailActivitiesImpl(gmail).fetchNewEmails(new FetchRequest("demo", null, 2, 50));
            assertThat(r.messages()).extracting(Message::id).containsExactly("gmail-a", "gmail-c");
            assertThat(r.skipped()).isEqualTo(1);
            assertThat(r.nextHistoryId()).isEqualTo("500");
            assertThat(r.hasMore()).isFalse();
        }

        @Test
        void incrementalFetchPagesAndCutsAtARecordBoundary() {
            FakeGmail gmail = new FakeGmail();
            for (int i = 1; i <= 5; i++) {
                gmail.messages.put("m" + i, email("m" + i, "x@y.com", "I'll do task " + i + " tomorrow.", "INBOX"));
                gmail.history.add(new HistoryRecord(String.valueOf(500 + i), List.of("m" + i)));
            }
            gmail.latestHistoryId = "505";
            var activities = new GmailActivitiesImpl(gmail);

            FetchResult first = activities.fetchNewEmails(new FetchRequest("demo", "500", 2, 3));
            assertThat(first.messages()).extracting(Message::id).containsExactly("gmail-m1", "gmail-m2", "gmail-m3");
            assertThat(first.hasMore()).isTrue();
            assertThat(first.nextHistoryId()).isEqualTo("503");

            FetchResult second = activities.fetchNewEmails(new FetchRequest("demo", first.nextHistoryId(), 2, 3));
            assertThat(second.messages()).extracting(Message::id).containsExactly("gmail-m4", "gmail-m5");
            assertThat(second.hasMore()).isFalse();
            assertThat(second.nextHistoryId()).isEqualTo("505");
        }

        @Test
        void expiredCursorTriggersAResync() {
            FakeGmail gmail = new FakeGmail();
            gmail.messages.put("a", email("a", "x@y.com", "I'll send it Monday.", "INBOX"));
            gmail.expired = true;
            FetchResult r = new GmailActivitiesImpl(gmail).fetchNewEmails(new FetchRequest("demo", "1", 2, 50));
            assertThat(r.resynced()).isTrue();
            assertThat(r.messages()).hasSize(1);
            assertThat(r.nextHistoryId()).isEqualTo("500");
        }
    }
}
