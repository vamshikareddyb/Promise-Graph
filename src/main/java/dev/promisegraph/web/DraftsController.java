package dev.promisegraph.web;

import dev.promisegraph.config.PromiseGraphProperties;
import dev.promisegraph.domain.Message;
import dev.promisegraph.domain.Promise;
import dev.promisegraph.drafts.DraftActivitiesImpl;
import dev.promisegraph.drafts.DraftContracts;
import dev.promisegraph.drafts.DraftContracts.DraftRequest;
import dev.promisegraph.drafts.DraftStore;
import dev.promisegraph.drafts.ReplyDraft;
import dev.promisegraph.inbox.InboxService;
import dev.promisegraph.ops.AuditEntry;
import dev.promisegraph.store.AuditLog;
import dev.promisegraph.store.Repository;
import io.temporal.client.WorkflowClient;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    public record PromiseRef(String id, String what, String owner, String due, String status) {}

    public record DraftView(String id, String to, String subject, String body, String status, String writtenBy, String createdAt,
            String updatedAt, List<PromiseRef> promises, String gmailUrl) {}

    public record Edit(String body) {}

    public record StatusChange(String status) {}

    public record Regenerate(String instruction) {}

    private final String tenant;
    private final DraftStore drafts;
    private final Repository repo;
    private final WorkflowClient client;
    private final AuditLog audit;

    public DraftsController(PromiseGraphProperties props, DraftStore drafts, Repository repo, WorkflowClient client, AuditLog audit) {
        this.tenant = props.tenant();
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
        DraftActivitiesImpl.start(client, new DraftRequest(tenant, source, instruction, true), workflowId);
        record(viewer, "draft:regenerate", id);
        return Map.of("status", "writing", "workflowId", workflowId);
    }

    @ExceptionHandler(InboxService.ActionException.class)
    public ResponseEntity<Map<String, String>> actionError(InboxService.ActionException e) {
        return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
    }
}
