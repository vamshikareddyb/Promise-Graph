package dev.promisegraph.web;

import dev.promisegraph.config.PromiseGraphProperties;
import dev.promisegraph.domain.Evidence;
import dev.promisegraph.domain.Message;
import dev.promisegraph.domain.Promise;
import dev.promisegraph.drafts.DraftActivitiesImpl;
import dev.promisegraph.drafts.DraftContracts;
import dev.promisegraph.drafts.DraftContracts.DraftRequest;
import dev.promisegraph.drafts.DraftStore;
import dev.promisegraph.drafts.EmailRef;
import dev.promisegraph.drafts.OllamaReplyDrafter;
import dev.promisegraph.gmail.MailboxOwner;
import dev.promisegraph.drafts.ReplyDraft;
import dev.promisegraph.drafts.ReplyDrafter;
import dev.promisegraph.drafts.TemplateReplyDrafter;
import dev.promisegraph.inbox.InboxService;
import dev.promisegraph.ops.AuditEntry;
import dev.promisegraph.store.AuditLog;
import dev.promisegraph.store.Repository;
import io.temporal.client.WorkflowClient;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reply drafts, shown only to the owner of the mailbox they were written for. Nothing here sends
 * mail: the person copies a draft into Gmail and marks it done.
 */
@RestController
@RequestMapping("/api/drafts")
public class DraftsController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DraftsController.class);

    public record PromiseRef(String id, String what, String owner, String due, String status) {}

    public record DraftView(String id, String to, String subject, String body, String status, String writtenBy, String createdAt,
            String updatedAt, List<PromiseRef> promises, String gmailUrl) {}

    public record Edit(String body) {}

    public record StatusChange(String status) {}

    public record Regenerate(String instruction) {}

    /** @param regenerate write a new version even if one exists */
    public record FollowUp(String instruction, boolean regenerate) {}

    private final String tenant;
    private final DraftStore drafts;
    private final Repository repo;
    private final WorkflowClient client;
    private final AuditLog audit;
    /** Who writes drafts in this run of the app: Claude, or the template (with why). */
    private final ReplyDrafter drafter;
    private final ObjectProvider<MailboxOwner> mailbox;

    public DraftsController(PromiseGraphProperties props, DraftStore drafts, Repository repo, WorkflowClient client, AuditLog audit,
            ObjectProvider<MailboxOwner> mailbox, ReplyDrafter drafter) {
        this.drafter = drafter;
        this.tenant = props.tenant();
        this.mailbox = mailbox;
        this.drafts = drafts;
        this.repo = repo;
        this.client = client;
        this.audit = audit;
    }

    private ReplyDraft own(String viewer, String id) {
        return drafts.get(tenant, id).filter(d -> d.owner().equalsIgnoreCase(viewer))
                .orElseThrow(() -> new InboxService.ActionException(404, "no such draft"));
    }

    private DraftView view(ReplyDraft d) {
        List<PromiseRef> ps = d.promiseIds().stream().map(id -> repo.getPromise(tenant, id)).flatMap(Optional::stream)
                .map((Promise p) -> new PromiseRef(p.id(), p.what(), p.owner(), p.due(), p.status().dbValue())).toList();
        String url = "https://mail.google.com/mail/?authuser=" + URLEncoder.encode(d.owner(), StandardCharsets.UTF_8) + "#all/"
                + URLEncoder.encode(d.threadId(), StandardCharsets.UTF_8);
        return new DraftView(d.id(), d.to(), d.subject(), d.body(), d.status(), d.writtenBy(), d.createdAt(), d.updatedAt(), ps, url);
    }

    private void record(String actor, String action, String target) {
        audit.record(new AuditEntry(tenant, Instant.now().toString(), actor, action, target, null));
    }

    @GetMapping
    public List<DraftView> list(@RequestHeader(name = ApiController.VIEWER, required = false) String viewerHeader) {
        String viewer = ApiController.require(viewerHeader);
        return drafts.forOwner(tenant, viewer).stream().map(this::view).toList();
    }

    @PutMapping("/{id}")
    public DraftView edit(@RequestHeader(name = ApiController.VIEWER, required = false) String viewerHeader, @PathVariable String id,
            @RequestBody Edit edit) {
        String viewer = ApiController.require(viewerHeader);
        if (edit.body() == null || edit.body().isBlank()) {
            throw new InboxService.ActionException(400, "the draft can't be empty");
        }
        ReplyDraft d = own(viewer, id).withBody(edit.body(), Instant.now().toString());
        drafts.update(d);
        record(viewer, "draft:edit", id);
        return view(d);
    }

    @PostMapping("/{id}/status")
    public DraftView status(@RequestHeader(name = ApiController.VIEWER, required = false) String viewerHeader, @PathVariable String id,
            @RequestBody StatusChange change) {
        String viewer = ApiController.require(viewerHeader);
        if (change.status() == null || !ReplyDraft.STATUSES.contains(change.status())) {
            throw new InboxService.ActionException(400, "status must be one of " + ReplyDraft.STATUSES);
        }
        ReplyDraft d = own(viewer, id).withStatus(change.status(), Instant.now().toString());
        drafts.update(d);
        record(viewer, "draft:" + change.status(), id);
        return view(d);
    }

    /** Claude writes the draft again, optionally following an instruction. The new version replaces the old one. */
    @PostMapping("/{id}/regenerate")
    public Map<String, String> regenerate(@RequestHeader(name = ApiController.VIEWER, required = false) String viewerHeader,
            @PathVariable String id, @RequestBody(required = false) Regenerate req) {
        String viewer = ApiController.require(viewerHeader);
        ReplyDraft d = own(viewer, id);
        String instruction = req == null || req.instruction() == null || req.instruction().isBlank() ? null : req.instruction().strip();
        Message source = new Message(d.sourceMessageId(), d.channel(), d.to(), d.createdAt(), "", tenant);
        String workflowId = DraftContracts.workflowId(tenant, d.sourceMessageId()) + "-r" + System.currentTimeMillis();
        DraftActivitiesImpl.start(client, new DraftRequest(tenant, source, instruction, true, null), workflowId);
        record(viewer, "draft:regenerate", id);
        return Map.of("status", "writing", "workflowId", workflowId);
    }

    // ------------------------------------------------------------ follow-ups on a promise

    /** "claude", "ollama:<model>", or "template: why no model is writing", for the page to explain a plain draft. */
    private String writer() {
        if (drafter instanceof TemplateReplyDrafter t) {
            return TemplateReplyDrafter.by(t.reason());
        }
        return drafter instanceof OllamaReplyDrafter o ? o.by() : "claude";
    }

    /** The latest email a promise was made or changed in, if it came from Gmail. */
    public static Optional<Evidence> emailEvidence(Promise p) {
        for (int i = p.evidence().size() - 1; i >= 0; i--) {
            Evidence e = p.evidence().get(i);
            if (EmailRef.of(e.channel(), e.messageId()).isPresent()) {
                return Optional.of(e);
            }
        }
        return Optional.empty();
    }

    /** Whose mailbox the email is in: the account in its channel (team mode), else the personal Gmail account. */
    public static Optional<String> mailboxOf(Evidence e, Optional<String> personal) {
        return EmailRef.of(e.channel(), e.messageId()).flatMap(r -> r.mailbox() != null ? Optional.of(r.mailbox()) : personal);
    }

    private Optional<String> personalOwner() {
        return Optional.ofNullable(mailbox.getIfAvailable()).flatMap(MailboxOwner::email);
    }

    /** The promise and its email, when the viewer owns the mailbox it came from. */
    private Map.Entry<Promise, Evidence> followable(String viewer, String promiseId) {
        Promise p = repo.getPromise(tenant, promiseId).orElseThrow(() -> new InboxService.ActionException(404, "no such promise"));
        Evidence e = emailEvidence(p).orElseThrow(() -> new InboxService.ActionException(409, "this promise didn't come from an email"));
        if (!mailboxOf(e, personalOwner()).map(viewer::equalsIgnoreCase).orElse(false)) {
            throw new InboxService.ActionException(403, "only the owner of the mailbox it came from can draft a follow-up");
        }
        return Map.entry(p, e);
    }

    /** The follow-up drafted for a promise, if any: {@code {"draft": ...}} or {@code {"draft": null}}. */
    @GetMapping("/promise/{promiseId}")
    public Map<String, Object> followUp(@RequestHeader(name = ApiController.VIEWER, required = false) String viewerHeader,
            @PathVariable String promiseId) {
        String viewer = ApiController.require(viewerHeader);
        followable(viewer, promiseId);
        Map<String, Object> out = new HashMap<>();
        out.put("writer", writer());
        out.put("draft", drafts.get(tenant, ReplyDraft.forPromise(tenant, promiseId)).filter(d -> d.owner().equalsIgnoreCase(viewer))
                .map(this::view).orElse(null));
        return out;
    }

    /**
     * Drafts a follow-up on a promise from the email thread it came from. Returns the existing draft
     * at once unless {@code regenerate}; otherwise Claude writes one in the background, and the page
     * asks for it again until it appears.
     */
    @PostMapping("/promise/{promiseId}")
    public Map<String, Object> draftFollowUp(@RequestHeader(name = ApiController.VIEWER, required = false) String viewerHeader,
            @PathVariable String promiseId, @RequestBody(required = false) FollowUp req) {
        String viewer = ApiController.require(viewerHeader);
        Map.Entry<Promise, Evidence> pe = followable(viewer, promiseId);
        Promise p = pe.getKey();
        Evidence e = pe.getValue();
        Optional<ReplyDraft> existing = drafts.get(tenant, ReplyDraft.forPromise(tenant, promiseId));
        boolean regenerate = req != null && req.regenerate();
        Map<String, Object> out = new HashMap<>();
        out.put("writer", writer());
        // A draft the template wrote before Claude was set up is replaced as soon as Claude can write it.
        boolean outdated = existing.isPresent() && existing.get().writtenBy() != null
                && existing.get().writtenBy().startsWith(TemplateReplyDrafter.BY) && !(drafter instanceof TemplateReplyDrafter);
        if (existing.isPresent() && !regenerate && !outdated) {
            out.put("status", "ready");
            out.put("draft", view(existing.get()));
            return out;
        }
        String instruction = req == null || req.instruction() == null || req.instruction().isBlank() ? null : req.instruction().strip();
        // The sender is only a fallback for who the follow-up goes to; the thread itself says who wrote what.
        String other = p.owner() != null && !p.owner().equalsIgnoreCase(viewer) ? p.owner() : p.recipient();
        Message source = new Message(e.messageId(), e.channel(), other, p.createdAt(), "", tenant);
        String workflowId = DraftContracts.workflowId(tenant, "promise-" + promiseId) + "-" + System.currentTimeMillis();
        DraftActivitiesImpl.start(client, DraftRequest.followUp(tenant, source, promiseId, instruction, existing.isPresent()), workflowId);
        record(viewer, existing.isPresent() ? "draft:rewrite" : "draft:follow-up", promiseId);
        out.put("status", "writing");
        out.put("previous", existing.map(ReplyDraft::updatedAt).orElse(null));
        return out;
    }

    /** Anything unexpected: say what went wrong on the page instead of a bare "Internal Server Error". */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, String>> unexpected(RuntimeException e) {
        log.error("drafts request failed", e);
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String detail = root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
        return ResponseEntity.status(500).body(Map.of("error", "Couldn't draft: " + detail.lines().findFirst().orElse(detail)));
    }

    @ExceptionHandler(InboxService.ActionException.class)
    public ResponseEntity<Map<String, String>> actionError(InboxService.ActionException e) {
        return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
    }
}
