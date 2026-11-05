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

    /** Draft replies automatically after extraction; otherwise only when the person asks. */
    private final boolean auto;
    private final WorkflowClient client;
    private final Repository repo;
    private final DraftStore store;
    /** The Gmail client for a mailbox (null: the personal one), if Gmail is connected. */
    private final Function<String, Optional<GmailApi>> gmail;
    /** Whose mailbox the personal Gmail is. */
    private final Supplier<Optional<String>> personalOwner;
    private final Clock clock;

    public DraftActivitiesImpl(boolean auto, WorkflowClient client, Repository repo, DraftStore store,
            Function<String, Optional<GmailApi>> gmail, Supplier<Optional<String>> personalOwner, Clock clock) {
        this.auto = auto;
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
        if (!auto) {
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
        boolean followUp = req.promiseId() != null;
        Optional<EmailRef> ref = EmailRef.of(m);
        if (ref.isEmpty()) {
            return Prepared.skip("not an email");
        }
        String owner = ref.get().mailbox() != null ? ref.get().mailbox() : personalOwner.get().orElse(null);
        if (owner == null) {
            return Prepared.skip("no mailbox owner");
        }
        // A reply answers someone else's email; a follow-up may start from the person's own.
        if (!followUp && m.sender() != null && m.sender().equalsIgnoreCase(owner)) {
            return Prepared.skip("you wrote this email");
        }
        if (!req.replace() && store.get(req.tenant(), ReplyDraft.idFor(req.tenant(), req.key())).isPresent()) {
            return Prepared.skip("already drafted");
        }
        Optional<Promise> focus = followUp ? repo.getPromise(req.tenant(), req.promiseId()) : Optional.empty();
        if (followUp && focus.isEmpty()) {
            return Prepared.skip("no such promise");
        }

        List<GmailMessage> raw = readThread(ref.get());
        List<ThreadMessage> thread = new ArrayList<>();
        String subject = null;
        String sourceReplyTo = null;
        String sourceTo = null;
        String lastOtherSender = null;
        boolean answered = false;
        long sourceAt = raw.stream().filter(g -> g.id().equals(ref.get().gmailId())).mapToLong(GmailMessage::internalDate).findFirst()
                .orElse(Long.MAX_VALUE);
        for (GmailMessage g : raw) {
            if (g.labelIds().contains("DRAFT")) {
                continue;
            }
            String from = GmailMapper.address(g.header("From"));
            boolean mine = from != null && from.equalsIgnoreCase(owner);
            if (subject == null && g.header("Subject") != null) {
                subject = g.header("Subject");
            }
            if (g.id().equals(ref.get().gmailId())) {
                sourceReplyTo = GmailMapper.address(g.header("Reply-To"));
                sourceTo = firstAddress(g.header("To"));
            }
            if (!mine && from != null) {
                lastOtherSender = from;
            }
            if (mine && g.internalDate() > sourceAt) {
                answered = true; // the person already replied after this email
            }
            String text = clip(Redactor.redact(EmailText.clean(g.plainText(), g.html())));
            if (!text.isBlank()) {
                thread.add(new ThreadMessage(from, Instant.ofEpochMilli(g.internalDate()).atOffset(ZoneOffset.UTC).toString(), text, mine));
            }
        }
        if (!followUp && answered && !req.replace()) {
            return Prepared.skip("already answered in Gmail");
        }
        if (thread.isEmpty() && m.text() != null && !m.text().isBlank()) {
            // no Gmail access: the one email
            thread.add(new ThreadMessage(m.sender(), m.ts(), clip(m.text()), m.sender() != null && m.sender().equalsIgnoreCase(owner)));
        }
        if (thread.size() > MAX_THREAD_MESSAGES) {
            thread = new ArrayList<>(thread.subList(thread.size() - MAX_THREAD_MESSAGES, thread.size()));
        }

        String replyTo = followUp
                ? firstOf(otherParty(focus.get(), owner), lastOtherSender, notMe(sourceReplyTo, owner), notMe(m.sender(), owner),
                        notMe(sourceTo, owner))
                : firstOf(sourceReplyTo, m.sender());
        List<PromiseNote> promises = new ArrayList<>(promisesAround(req.tenant(), m.channel(), owner, replyTo));
        if (focus.isPresent() && promises.stream().noneMatch(p -> p.id().equals(req.promiseId()))) {
            promises.add(0, note(focus.get(), true)); // made in another thread than its evidence: still the subject
        }
        return new Prepared(new DraftContext(req.tenant(), owner, ref.get().mailbox(), ref.get().threadId(), m.channel(), m.id(),
                replyTo, subject, thread, promises, req.instruction(), Instant.now(clock).toString(), req.promiseId()), null);
    }

    /** Whoever the promise is between besides the person: its owner, or who it was made to. */
    private static String otherParty(Promise p, String me) {
        String other = notMe(p.owner(), me);
        return other != null ? other : notMe(p.recipient(), me);
    }

    private static String notMe(String address, String me) {
        return address == null || address.isBlank() || address.equalsIgnoreCase(me) || !address.contains("@") ? null : address;
    }

    private static String firstOf(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isBlank()) {
                return c;
            }
        }
        return null;
    }

    /** The first address in a header like {@code "A <a@x.com>, b@y.com"}. */
    static String firstAddress(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        return GmailMapper.address(header.split(",")[0]);
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
