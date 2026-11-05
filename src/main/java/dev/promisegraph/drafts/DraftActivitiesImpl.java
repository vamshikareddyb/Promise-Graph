package dev.promisegraph.drafts;

import static dev.promisegraph.config.PromiseGraphProperties.TASK_QUEUE_MAIN;

import dev.promisegraph.domain.Evidence;
import dev.promisegraph.domain.Message;
import dev.promisegraph.domain.Promise;
import dev.promisegraph.domain.PromiseStatus;
import dev.promisegraph.drafts.DraftContracts.DraftContext;
import dev.promisegraph.drafts.DraftContracts.DraftRequest;
import dev.promisegraph.drafts.DraftContracts.Prepared;
import dev.promisegraph.drafts.DraftContracts.PromiseNote;
import dev.promisegraph.drafts.DraftContracts.ThreadMessage;
import dev.promisegraph.gmail.EmailText;
import dev.promisegraph.gmail.GmailApi;
import dev.promisegraph.gmail.GmailApi.GmailApiException;
import dev.promisegraph.gmail.GmailMapper;
import dev.promisegraph.gmail.GmailMessage;
import dev.promisegraph.privacy.Redactor;
import dev.promisegraph.store.Repository;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import io.temporal.failure.ApplicationFailure;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DraftActivitiesImpl implements DraftActivities {

    private static final Logger log = LoggerFactory.getLogger(DraftActivitiesImpl.class);

    /** The thread is cut to its latest emails, and each email to this length, to keep the prompt small. */
    static final int MAX_THREAD_MESSAGES = 12;
    static final int MAX_TEXT = 3000;
    static final int MAX_PROMISES = 20;

    private final boolean enabled;
    private final WorkflowClient client;
    private final Repository repo;
    private final DraftStore store;
    /** The Gmail client for a mailbox (null: the personal one), if Gmail is connected. */
    private final Function<String, Optional<GmailApi>> gmail;
    /** Whose mailbox the personal Gmail is. */
    private final Supplier<Optional<String>> personalOwner;
    private final Clock clock;

    public DraftActivitiesImpl(boolean enabled, WorkflowClient client, Repository repo, DraftStore store,
            Function<String, Optional<GmailApi>> gmail, Supplier<Optional<String>> personalOwner, Clock clock) {
        this.enabled = enabled;
        this.client = client;
        this.repo = repo;
        this.store = store;
        this.gmail = gmail;
        this.personalOwner = personalOwner;
        this.clock = clock;
    }

    // ------------------------------------------------------------ start

    @Override
    public void startDrafts(List<DraftRequest> requests) {
        if (!enabled) {
            return;
        }
        for (DraftRequest r : requests) {
            start(client, r, DraftContracts.workflowId(r.tenant(), r.message().id()));
        }
    }

    /** Starts a draft workflow under this ID; does nothing if it was already started. */
    public static void start(WorkflowClient client, DraftRequest request, String workflowId) {
        DraftReplyWorkflow wf = client.newWorkflowStub(DraftReplyWorkflow.class, WorkflowOptions.newBuilder()
                .setWorkflowId(workflowId)
                .setTaskQueue(TASK_QUEUE_MAIN)
                .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                .build());
        try {
            WorkflowClient.start(wf::run, request);
        } catch (WorkflowExecutionAlreadyStarted e) {
            // a retry, or this email was already drafted
        }
    }

    // ------------------------------------------------------------ gather the context

    @Override
    public Prepared prepareDraft(DraftRequest req) {
        Message m = req.message();
        Optional<EmailRef> ref = EmailRef.of(m);
        if (ref.isEmpty()) {
            return Prepared.skip("not an email");
        }
        String owner = ref.get().mailbox() != null ? ref.get().mailbox() : personalOwner.get().orElse(null);
        if (owner == null) {
            return Prepared.skip("no mailbox owner");
        }
        if (m.sender() != null && m.sender().equalsIgnoreCase(owner)) {
            return Prepared.skip("you wrote this email");
        }
        String draftId = ReplyDraft.idFor(req.tenant(), m.id());
        if (!req.replace() && store.get(req.tenant(), draftId).isPresent()) {
            return Prepared.skip("already drafted");
        }

        List<GmailMessage> raw = readThread(ref.get());
        List<ThreadMessage> thread = new ArrayList<>();
        String subject = null;
        String replyTo = m.sender();
        boolean answered = false;
        long sourceAt = raw.stream().filter(g -> g.id().equals(ref.get().gmailId())).mapToLong(GmailMessage::internalDate).findFirst()
                .orElse(Long.MAX_VALUE);
        for (GmailMessage g : raw) {
            String from = GmailMapper.address(g.header("From"));
            boolean mine = from != null && from.equalsIgnoreCase(owner);
            if (subject == null && g.header("Subject") != null) {
                subject = g.header("Subject");
            }
            if (g.id().equals(ref.get().gmailId()) && g.header("Reply-To") != null) {
                replyTo = GmailMapper.address(g.header("Reply-To"));
            }
            if (mine && g.internalDate() > sourceAt && !g.labelIds().contains("DRAFT")) {
                answered = true; // the person already replied after this email
            }
            if (g.labelIds().contains("DRAFT")) {
                continue;
            }
            String text = clip(Redactor.redact(EmailText.clean(g.plainText(), g.html())));
            if (!text.isBlank()) {
                thread.add(new ThreadMessage(from, Instant.ofEpochMilli(g.internalDate()).atOffset(ZoneOffset.UTC).toString(), text, mine));
            }
        }
        if (answered && !req.replace()) {
            return Prepared.skip("already answered in Gmail");
        }
        if (thread.isEmpty() && m.text() != null && !m.text().isBlank()) {
            thread.add(new ThreadMessage(m.sender(), m.ts(), clip(m.text()), false)); // no Gmail access: the one email
        }
        if (thread.size() > MAX_THREAD_MESSAGES) {
            thread = new ArrayList<>(thread.subList(thread.size() - MAX_THREAD_MESSAGES, thread.size()));
        }

        List<PromiseNote> promises = promisesAround(req.tenant(), m.channel(), owner, replyTo == null ? m.sender() : replyTo);
        return new Prepared(new DraftContext(req.tenant(), owner, ref.get().mailbox(), ref.get().threadId(), m.channel(), m.id(),
                replyTo == null ? m.sender() : replyTo, subject, thread, promises, req.instruction(), Instant.now(clock).toString()), null);
    }

    private List<GmailMessage> readThread(EmailRef ref) {
        Optional<GmailApi> api;
        try {
            api = gmail.apply(ref.mailbox());
        } catch (RuntimeException e) {
            log.warn("no Gmail client for {}: {}", ref.mailbox(), e.getMessage());
            return List.of();
        }
        if (api.isEmpty()) {
            return List.of();
        }
        try {
            return api.get().thread(ref.threadId());
        } catch (GmailApiException e) {
            if (e.retryable()) {
                throw ApplicationFailure.newFailureWithCause(e.getMessage(), "GmailUnavailable", e);
            }
            log.warn("could not read thread {} for a draft ({}); drafting from the one email", ref.threadId(), e.getMessage());
            return List.of();
        }
    }

    /**
     * Promises made in this thread first, then open promises between the same two people elsewhere,
     * so the reply can stay consistent with what else is already agreed.
     */
    List<PromiseNote> promisesAround(String tenant, String channel, String owner, String other) {
        List<PromiseNote> here = new ArrayList<>();
        List<PromiseNote> between = new ArrayList<>();
        for (Promise p : repo.promisesByStatus(tenant, EnumSet.allOf(PromiseStatus.class))) {
            boolean inThread = channel.equals(p.channel()) || p.evidence().stream().anyMatch(e -> channel.equals(e.channel()));
            if (inThread) {
                here.add(note(p, true));
            } else if (p.status().isOpen() && other != null && pair(p, owner, other)) {
                between.add(note(p, false));
            }
        }
        List<PromiseNote> out = new ArrayList<>(here);
        out.addAll(between);
        return out.size() > MAX_PROMISES ? List.copyOf(out.subList(0, MAX_PROMISES)) : out;
    }

    private static boolean pair(Promise p, String a, String b) {
        String o = p.owner() == null ? "" : p.owner();
        String r = p.recipient() == null ? "" : p.recipient();
        return (o.equalsIgnoreCase(a) && r.equalsIgnoreCase(b)) || (o.equalsIgnoreCase(b) && r.equalsIgnoreCase(a));
    }

    private static PromiseNote note(Promise p, boolean thisThread) {
        List<Evidence> ev = p.evidence();
        return new PromiseNote(p.id(), p.what(), p.owner(), p.recipient(), p.due(), p.status().dbValue(),
                ev.isEmpty() ? null : ev.get(ev.size() - 1).quote(), thisThread);
    }

    private static String clip(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= MAX_TEXT ? s : s.substring(0, MAX_TEXT) + " …";
    }

    // ------------------------------------------------------------ save

    @Override
    public void saveDraft(ReplyDraft draft, boolean replace) {
        if (store.save(draft, replace)) {
            log.info("reply drafted for {} ({}) by {}", draft.owner(), draft.subject(), draft.writtenBy());
        }
    }
}
