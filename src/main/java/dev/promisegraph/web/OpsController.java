package dev.promisegraph.web;

import dev.promisegraph.config.PromiseGraphProperties;
import dev.promisegraph.inbox.InboxService;
import dev.promisegraph.ingestion.ExtractionInput;
import dev.promisegraph.ops.AuditEntry;
import dev.promisegraph.ops.DeadLetter;
import dev.promisegraph.privacy.Visibility;
import dev.promisegraph.store.AuditLog;
import dev.promisegraph.store.DeadLetterStore;
import dev.promisegraph.temporal.ExtractionWorkflow;
import dev.promisegraph.temporal.ReconcileWorkflow;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The operations page: dead letters, the reconciliation sweep and the audit log. Leads and admins only. */
@RestController
@RequestMapping("/api/ops")
public class OpsController {

    private final String tenant;
    private final DeadLetterStore deadLetters;
    private final AuditLog audit;
    private final Visibility visibility;
    private final WorkflowClient client;

    public OpsController(PromiseGraphProperties props, DeadLetterStore deadLetters, AuditLog audit, Visibility visibility, WorkflowClient client) {
        this.tenant = props.tenant();
        this.deadLetters = deadLetters;
        this.audit = audit;
        this.visibility = visibility;
        this.client = client;
    }

    private String operator(String viewerHeader) {
        String viewer = ApiController.require(viewerHeader);
        if (!visibility.canOperate(viewer)) {
            throw new InboxService.ActionException(403, "the operations page is for team leads and admins");
        }
        return viewer;
    }

    @GetMapping("/dead-letters")
    public List<DeadLetter> deadLetters(@RequestHeader(name = ApiController.VIEWER, required = false) String viewer,
            @RequestParam(defaultValue = "false") boolean all) {
        operator(viewer);
        return deadLetters.list(tenant, !all);
    }

    /** Runs a failed batch again under its original workflow ID, so its messages are still its own to claim. */
    @PostMapping("/dead-letters/{id}/retry")
    public Map<String, String> retry(@RequestHeader(name = ApiController.VIEWER, required = false) String viewerHeader, @PathVariable String id) {
        String viewer = operator(viewerHeader);
        DeadLetter d = deadLetters.get(tenant, id).orElseThrow(() -> new InboxService.ActionException(404, "no such dead letter"));
        if (!"extraction_failed".equals(d.kind()) || !"open".equals(d.status())) {
            throw new InboxService.ActionException(409, "only open failed batches can be retried");
        }
        WorkflowOptions options = WorkflowOptions.newBuilder()
                .setWorkflowId(d.reference())
                .setTaskQueue(PromiseGraphProperties.TASK_QUEUE_MAIN)
                .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE)
                .build();
        try {
            WorkflowClient.start(client.newWorkflowStub(ExtractionWorkflow.class, options)::run, new ExtractionInput(tenant, d.messages()));
        } catch (WorkflowExecutionAlreadyStarted e) {
            throw new InboxService.ActionException(409, "that batch is running already");
        }
        deadLetters.setStatus(tenant, id, "retried");
        audit.record(new AuditEntry(tenant, Instant.now().toString(), viewer, "dead-letter:retry", id, null));
        return Map.of("status", "retrying");
    }

    @PostMapping("/dead-letters/{id}/dismiss")
    public Map<String, String> dismiss(@RequestHeader(name = ApiController.VIEWER, required = false) String viewerHeader, @PathVariable String id) {
        String viewer = operator(viewerHeader);
        if (!deadLetters.setStatus(tenant, id, "dismissed")) {
            throw new InboxService.ActionException(404, "no such dead letter");
        }
        audit.record(new AuditEntry(tenant, Instant.now().toString(), viewer, "dead-letter:dismiss", id, null));
        return Map.of("status", "dismissed");
    }

    /** Runs the sweep now instead of waiting for the hourly schedule. */
    @PostMapping("/reconcile")
    public Map<String, String> reconcile(@RequestHeader(name = ApiController.VIEWER, required = false) String viewerHeader) {
        String viewer = operator(viewerHeader);
        String workflowId = "reconcile-manual-" + tenant + "-" + Instant.now().getEpochSecond();
        WorkflowClient.start(client.newWorkflowStub(ReconcileWorkflow.class, WorkflowOptions.newBuilder()
                .setWorkflowId(workflowId).setTaskQueue(PromiseGraphProperties.TASK_QUEUE_MAIN).build())::run, tenant);
        audit.record(new AuditEntry(tenant, Instant.now().toString(), viewer, "reconcile:run", workflowId, null));
        return Map.of("status", "started", "workflowId", workflowId);
    }

    @GetMapping("/audit")
    public List<AuditEntry> audit(@RequestHeader(name = ApiController.VIEWER, required = false) String viewer,
            @RequestParam(defaultValue = "200") int limit) {
        operator(viewer);
        return audit.recent(tenant, Math.max(1, Math.min(limit, 1000)));
    }

    @ExceptionHandler(InboxService.ActionException.class)
    public ResponseEntity<Map<String, String>> actionError(InboxService.ActionException e) {
        return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
    }
}
