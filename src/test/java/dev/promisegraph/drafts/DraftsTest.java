package dev.promisegraph.drafts;

import static dev.promisegraph.config.PromiseGraphProperties.TASK_QUEUE_MAIN;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.promisegraph.TestStack;
import dev.promisegraph.domain.Commitment;
import dev.promisegraph.domain.CommitmentKind;
import dev.promisegraph.domain.Evidence;
import dev.promisegraph.domain.Message;
import dev.promisegraph.domain.Promise;
import dev.promisegraph.domain.PromiseStatus;
import dev.promisegraph.drafts.DraftContracts.DraftContext;
import dev.promisegraph.drafts.DraftContracts.DraftRequest;
import dev.promisegraph.drafts.DraftContracts.Prepared;
import dev.promisegraph.drafts.DraftContracts.PromiseNote;
import dev.promisegraph.drafts.DraftContracts.ThreadMessage;
import dev.promisegraph.gmail.GmailApi;
import dev.promisegraph.gmail.GmailMessage;
import dev.promisegraph.store.InMemoryRepository;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The reply-draft agent: which emails get a draft, what context it sees, and the workflow end to end. */
class DraftsTest {

    static final String ME = "me@example.com";
    static final Message EMAIL = new Message("gmail-abc123", "email:t9", "neha.kapoor@example.com", "2026-11-04T09:00:00Z",
            "Thanks! I'll send the pricing deck by Friday. Can you review the contract by 12 Nov?", "demo");

    static GmailMessage gm(String id, long at, String from, String text, String... labels) {
        return new GmailMessage(id, "t9", List.of(labels), at, Map.of("From", from, "Subject", "Pricing"), text, null);
    }

    /** A fake Gmail that only knows one thread. */
    static GmailApi gmail(List<GmailMessage> thread) {
        return new GmailApi() {
            public Profile profile() { return new Profile(ME, "1"); }
            public HistoryPage history(String start, String token) { return new HistoryPage(List.of(), null, start); }
            public MessageList listMessages(String q, String token, int max) { return new MessageList(List.of(), null); }
            public GmailMessage getMessage(String id) { throw new UnsupportedOperationException(); }
            @Override public List<GmailMessage> thread(String threadId) { return thread; }
        };
    }

    static DraftActivitiesImpl activities(InMemoryRepository repo, DraftStore store, Optional<GmailApi> api) {
        return new DraftActivitiesImpl(true, null, repo, store, box -> api, () -> Optional.of(ME), Clock.fixed(Instant.parse("2026-11-04T10:00:00Z"), ZoneOffset.UTC));
    }

    static Promise promise(String id, String owner, String recipient, String what, String due, String channel) {
        return new Promise(id, "demo", owner, what, PromiseStatus.ACTIVE, 0.9, "2026-11-04T09:00:00Z", "2026-11-04T09:00:00Z", recipient,
                due, channel, List.of(new Evidence("gmail-abc123", what, channel)), 1);
    }

    @Test
    void emailRefsComeFromTheGmailIds() {
        assertThat(EmailRef.of(EMAIL)).contains(new EmailRef(null, "t9", "abc123"));
        assertThat(EmailRef.of("email:me@x.com:t1", "gmail-9f3e2a-18c2")).contains(new EmailRef("me@x.com", "t1", "18c2"));
        assertThat(EmailRef.of("slack:general", "m1")).isEmpty();
    }

    @Test
    void onlyEmailsWithPromisesAreDrafted() {
        Message chat = new Message("m1", "slack:general", "neha", "2026-11-04T09:00:00Z", "I'll ship it", "demo");
        Message quiet = new Message("gmail-zzz", "email:t2", "x@example.com", "2026-11-04T09:00:00Z", "lunch?", "demo");
        Commitment c = new Commitment(CommitmentKind.NEW, EMAIL.id(), "neha.kapoor@example.com", "send the pricing deck",
                "I'll send the pricing deck by Friday", 0.9, EMAIL.channel(), null, "2026-11-06", null);
        Commitment onChat = new Commitment(CommitmentKind.NEW, "m1", "neha", "ship it", "I'll ship it", 0.9, "slack:general", null, null, null);
        assertThat(DraftRequest.forEmails("demo", List.of(EMAIL, chat, quiet), List.of(c, onChat)))
                .singleElement().satisfies(r -> assertThat(r.message().id()).isEqualTo(EMAIL.id()));
    }

    @Test
    void contextHasTheThreadAndThePromisesAroundIt() {
        InMemoryRepository repo = new InMemoryRepository();
        repo.createPromise(promise("p1", "neha.kapoor@example.com", ME, "send the pricing deck", "2026-11-06", "email:t9"));
        repo.createPromise(promise("p2", ME, "neha.kapoor@example.com", "review the contract", "2026-11-12", "email:t9"));
        repo.createPromise(promise("p3", ME, "neha.kapoor@example.com", "share the Q4 numbers", "2026-11-20", "email:old"));
        repo.createPromise(promise("p4", ME, "someone@else.com", "unrelated", "2026-11-20", "email:other"));
        GmailApi api = gmail(List.of(
                gm("aaa", 1000, ME, "Hi Neha, can you send the deck?"),
                gm("abc123", 2000, "Neha Kapoor <neha.kapoor@example.com>", EMAIL.text())));

        Prepared p = activities(repo, new InMemoryDraftStore(), Optional.of(api)).prepareDraft(DraftRequest.first("demo", EMAIL));

        DraftContext ctx = p.context();
        assertThat(ctx.owner()).isEqualTo(ME);
        assertThat(ctx.replyTo()).isEqualTo("neha.kapoor@example.com");
        assertThat(ctx.subject()).isEqualTo("Pricing");
        assertThat(ctx.thread()).extracting(ThreadMessage::mine).containsExactly(true, false);
        assertThat(ctx.promises()).extracting(PromiseNote::id).containsExactlyInAnyOrder("p1", "p2", "p3");
        assertThat(ctx.promises().get(2).id()).isEqualTo("p3"); // this thread first, background after
        assertThat(ctx.promises()).filteredOn(PromiseNote::thisThread).extracting(PromiseNote::id).containsExactlyInAnyOrder("p1", "p2");
    }

    @Test
    void skipsOwnMailAnsweredMailAndMailAlreadyDrafted() {
        InMemoryRepository repo = new InMemoryRepository();
        Message mine = new Message("gmail-x1", "email:t9", ME, "2026-11-04T09:00:00Z", "I'll send it", "demo");
        assertThat(activities(repo, new InMemoryDraftStore(), Optional.empty()).prepareDraft(DraftRequest.first("demo", mine)).skipped())
                .isEqualTo("you wrote this email");

        GmailApi answered = gmail(List.of(gm("abc123", 2000, "neha.kapoor@example.com", EMAIL.text()), gm("bbb", 3000, ME, "Sure!")));
        assertThat(activities(repo, new InMemoryDraftStore(), Optional.of(answered)).prepareDraft(DraftRequest.first("demo", EMAIL)).skipped())
                .isEqualTo("already answered in Gmail");

        InMemoryDraftStore store = new InMemoryDraftStore();
        DraftActivitiesImpl a = activities(repo, store, Optional.empty());
        Prepared first = a.prepareDraft(DraftRequest.first("demo", EMAIL));
        a.saveDraft(ReplyDraft.of(first.context(), new DraftContracts.Written("Hi", "template"), "2026-11-04T10:00:00Z"), false);
        assertThat(a.prepareDraft(DraftRequest.first("demo", EMAIL)).skipped()).isEqualTo("already drafted");
        assertThat(a.prepareDraft(new DraftRequest("demo", EMAIL, "shorter", true, null)).context()).isNotNull();
    }

    @Test
    void followUpOnYourOwnEmailGoesToTheOtherPerson() {
        InMemoryRepository repo = new InMemoryRepository();
        repo.createPromise(promise("p9", ME, null, "send the project report", "2026-11-06", "email:t9"));
        Message mine = new Message("gmail-abc123", "email:t9", ME, "2026-11-04T09:00:00Z", "I will send the project report by friday", "demo");
        GmailApi api = gmail(List.of(new GmailMessage("abc123", "t9", List.of(), 2000,
                Map.of("From", ME, "To", "Neha Kapoor <neha.kapoor@example.com>, x@y.com", "Subject", "Report"), mine.text(), null)));

        Prepared p = activities(repo, new InMemoryDraftStore(), Optional.of(api))
                .prepareDraft(DraftRequest.followUp("demo", mine, "p9", null, false));

        assertThat(p.context().replyTo()).isEqualTo("neha.kapoor@example.com");
        assertThat(p.context().focus()).hasValueSatisfying(f -> assertThat(f.id()).isEqualTo("p9"));
        String body = TemplateReplyDrafter.compose(p.context());
        assertThat(body).contains("I'm on track to send the project report by Friday 6 Nov");
        ReplyDraft d = ReplyDraft.of(p.context(), new DraftContracts.Written(body, "template"), "2026-11-04T10:00:00Z");
        assertThat(d.id()).isEqualTo(ReplyDraft.forPromise("demo", "p9"));
        assertThat(d.promiseIds()).containsExactly("p9");
    }

    @Test
    void templateConfirmsBothSidesOfThePromises() {
        DraftContext ctx = new DraftContext("demo", ME, null, "t9", "email:t9", EMAIL.id(), "neha.kapoor@example.com", "Pricing",
                List.of(), List.of(new PromiseNote("p1", "Send the pricing deck", "neha.kapoor@example.com", ME, "2026-11-06", "active", null, true),
                        new PromiseNote("p2", "review the contract", ME, "neha.kapoor@example.com", "2026-11-12", "active", null, true)),
                null, "2026-11-04T10:00:00Z", null);
        String body = TemplateReplyDrafter.compose(ctx);
        assertThat(body).startsWith("Hi Neha,")
                .contains("Noted that you'll send the pricing deck by Friday 6 Nov")
                .contains("Confirming I'll review the contract by Thursday 12 Nov");
    }

    @Test
    void claudeGetsTheContextAndItsReplyIsCleanedUp() {
        ObjectMapper json = new ObjectMapper();
        List<ObjectNode> sent = new ArrayList<>();
        ClaudeReplyDrafter claude = new ClaudeReplyDrafter(req -> {
            sent.add(req);
            ObjectNode res = json.createObjectNode();
            res.putArray("content").addObject().put("type", "text").put("text", "Subject: Re: Pricing\n\nHi Neha,\n\nWill do.\n\nBest,");
            return res;
        }, "claude-sonnet-5-5", 800);
        DraftContext ctx = new DraftContext("demo", ME, null, "t9", "email:t9", EMAIL.id(), "neha.kapoor@example.com", "Pricing",
                List.of(new ThreadMessage("neha.kapoor@example.com", "2026-11-04T09:00:00Z", EMAIL.text(), false)), List.of(),
                "keep it short", "2026-11-04T10:00:00Z", null);

        DraftContracts.Written w = claude.write(ctx);

        assertThat(w.body()).isEqualTo("Hi Neha,\n\nWill do.\n\nBest,");
        assertThat(w.by()).isEqualTo("claude-sonnet-5-5");
        assertThat(sent).singleElement().satisfies(r -> {
            assertThat(r.path("model").asText()).isEqualTo("claude-sonnet-5-5");
            assertThat(r.path("messages").get(0).path("content").asText()).contains("keep it short").contains("pricing deck");
        });
    }

    @Test
    void workflowDraftsAReplyForTheMailboxOwner() {
        try (TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance()) {
            TestStack stack = new TestStack(env);
            stack.register(env);
            env.start();
            // Team-mode channel: the mailbox is in the channel, so no personal owner is needed.
            Message email = new Message("gmail-1a2b3c-abc123", "email:" + ME + ":t9", "neha.kapoor@example.com", "2026-11-04T09:00:00Z",
                    EMAIL.text(), "demo");
            stack.repo.createPromise(promise("p1", "neha.kapoor@example.com", ME, "send the pricing deck", "2026-11-06", email.channel()));
            WorkflowClient client = env.getWorkflowClient();
            DraftReplyWorkflow wf = client.newWorkflowStub(DraftReplyWorkflow.class, WorkflowOptions.newBuilder()
                    .setWorkflowId(DraftContracts.workflowId("demo", email.id())).setTaskQueue(TASK_QUEUE_MAIN).build());

            assertThat(wf.run(DraftRequest.first("demo", email))).isEqualTo("drafted");

            assertThat(stack.drafts.forOwner("demo", ME)).singleElement().satisfies(d -> {
                assertThat(d.to()).isEqualTo("neha.kapoor@example.com");
                assertThat(d.subject()).isEqualTo("Re: (no subject)");
                assertThat(d.body()).contains("Noted that you'll send the pricing deck");
                assertThat(d.promiseIds()).containsExactly("p1");
                assertThat(d.status()).isEqualTo("ready");
            });
        }
    }
}
