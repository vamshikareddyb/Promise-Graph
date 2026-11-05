package dev.promisegraph.web;

import dev.promisegraph.config.AuthProperties;
import dev.promisegraph.config.HandoffProperties;
import dev.promisegraph.config.PromiseGraphProperties;
import dev.promisegraph.gmail.GmailSyncContracts;
import dev.promisegraph.gmail.GmailSyncWorkflow;
import dev.promisegraph.inbox.InboxService;
import dev.promisegraph.ops.AuditEntry;
import dev.promisegraph.privacy.Visibility;
import dev.promisegraph.store.AuditLog;
import dev.promisegraph.team.Member;
import dev.promisegraph.team.TeamStore;
import io.temporal.client.WorkflowClient;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Who is signed in, and (team mode) the People page that admins keep up to date. */
@RestController
@RequestMapping("/api")
public class TeamController {

    /** What an admin can change about a person. */
    public record MemberEdit(String name, String team, String role, Boolean lead, Boolean admin, Boolean active) {}

    private final AuthProperties auth;
    private final ObjectProvider<TeamStore> team;
    private final Visibility visibility;
    private final AuditLog audit;
    private final String tenant;
    private final ObjectProvider<WorkflowClient> client;
    private final boolean workerEnabled;
    private final int handoffPrepDays;

    public TeamController(AuthProperties auth, ObjectProvider<TeamStore> team, Visibility visibility, AuditLog audit,
            PromiseGraphProperties props, ObjectProvider<WorkflowClient> client, HandoffProperties handoff) {
        this.handoffPrepDays = handoff.prepDays();
        this.client = client;
        this.workerEnabled = props.temporal().workerEnabled();
        this.auth = auth;
        this.team = team;
        this.visibility = visibility;
        this.audit = audit;
        this.tenant = props.tenant();
    }

    /** {@code mode}: "none" (pick who you are) or "google" (signed in). In team mode, 401 until signed in. */
    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(@RequestHeader(name = ApiController.VIEWER, required = false) String viewer) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", auth.mode());
        out.put("handoffPrepDays", handoffPrepDays); // cover is planned this many days before an absence
        if (!auth.team()) {
            return ResponseEntity.ok(out);
        }
        if (viewer == null || viewer.isBlank()) {
            out.put("signedIn", false);
            return ResponseEntity.status(401).body(out);
        }
        Member m = team.getObject().member(tenant, viewer).orElse(null);
        out.put("signedIn", true);
        out.put("email", viewer);
        out.put("name", m == null ? viewer : m.name());
        out.put("team", m == null ? "" : m.team());
        out.put("admin", visibility.canOperate(viewer));
        out.put("gmailProblem", gmailProblem(viewer));
        return ResponseEntity.ok(out);
    }

    /** Why this person's mailbox can't be read right now, or null. */
    private String gmailProblem(String email) {
        WorkflowClient c = client.getIfAvailable();
        if (!workerEnabled || c == null) {
            return null;
        }
        try {
            return c.newWorkflowStub(GmailSyncWorkflow.class, GmailSyncContracts.workflowId(tenant, email)).problem();
        } catch (RuntimeException e) {
            return null; // no sync yet, or Temporal busy: nothing useful to show
        }
    }

    private TeamStore store() {
        TeamStore s = team.getIfAvailable();
        if (s == null) {
            throw new InboxService.ActionException(404, "the People page is for team mode (PG_AUTH=google)");
        }
        return s;
    }

    /** Everyone in the team, for choosing cover and showing names. Any signed-in person may read it. */
    @GetMapping("/team")
    public List<Member> members(@RequestHeader(name = ApiController.VIEWER, required = false) String viewer) {
        ApiController.require(viewer);
        return store().members(tenant);
    }

    @PutMapping("/team/{email}")
    public Member edit(@RequestHeader(name = ApiController.VIEWER, required = false) String viewer, @PathVariable String email,
            @RequestBody MemberEdit edit) {
        String who = ApiController.require(viewer);
        if (!visibility.canOperate(who)) {
            throw new InboxService.ActionException(403, "only admins can edit the People page");
        }
        TeamStore s = store();
        Member m = s.member(tenant, email).orElseThrow(() -> new InboxService.ActionException(404, "nobody with that email has signed in"));
        boolean admin = edit.admin() == null ? m.admin() : edit.admin();
        if (who.equalsIgnoreCase(m.email()) && !admin) {
            throw new IllegalArgumentException("you can't remove your own admin rights; ask another admin");
        }
        Member updated = m.edited(edit.name() == null ? m.name() : edit.name(), edit.team() == null ? m.team() : edit.team(),
                edit.role() == null ? m.role() : edit.role(), edit.lead() == null ? m.lead() : edit.lead(), admin,
                edit.active() == null ? m.active() : edit.active());
        s.update(updated);
        audit.record(new AuditEntry(tenant, Instant.now().toString(), who, "edit_person", m.email(),
                "team=" + updated.team() + " role=" + updated.role() + " lead=" + updated.lead() + " admin=" + updated.admin()
                        + " active=" + updated.active()));
        return updated;
    }

    @ExceptionHandler(InboxService.ActionException.class)
    public ResponseEntity<Map<String, String>> actionError(InboxService.ActionException e) {
        return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
