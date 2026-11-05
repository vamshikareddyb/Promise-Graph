package dev.promisegraph.web;

import dev.promisegraph.config.AuthProperties;
import dev.promisegraph.config.PromiseGraphProperties;
import dev.promisegraph.domain.Person;
import dev.promisegraph.domain.Promise;
import dev.promisegraph.domain.PromiseStatus;
import dev.promisegraph.handoff.Absence;
import dev.promisegraph.gmail.MailboxOwner;
import dev.promisegraph.handoff.Directory;
import dev.promisegraph.handoff.HandoffRecord;
import dev.promisegraph.handoff.HandoffScheduler;
import dev.promisegraph.inbox.InboxItem;
import dev.promisegraph.inbox.InboxService;
import dev.promisegraph.inbox.Signals;
import dev.promisegraph.links.Link;
import dev.promisegraph.ops.AuditEntry;
import dev.promisegraph.privacy.Visibility;
import dev.promisegraph.recovery.NegotiationRecord;
import dev.promisegraph.store.AuditLog;
import dev.promisegraph.store.HandoffStore;
import dev.promisegraph.store.InboxStore;
import dev.promisegraph.store.LinkStore;
import dev.promisegraph.store.NegotiationStore;
import dev.promisegraph.store.Repository;
import dev.promisegraph.temporal.Contracts.HandoffResponse;
import dev.promisegraph.temporal.Contracts.PromiseSignal;
import dev.promisegraph.temporal.NegotiationContracts.Vote;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The JSON API behind the web app. Every request says who is viewing ({@value #VIEWER}), and
 * every answer is filtered by {@link Visibility}; every change is recorded in the audit log.
 *
 * <p>There is no login yet: the viewer is whoever the browser says, so the server listens on
 * localhost only (see {@code server.address}). The rules are enforced here, server-side, so
 * adding real sign-in later only has to replace where the viewer comes from.
 */
@RestController
@RequestMapping("/api")
public class ApiController {

    public static final String VIEWER = "X-PG-Viewer";

    public record PersonView(String id, String name, String team, String role, boolean lead) {}

    /**
     * @param recipient who the promise was made to, if known
     * @param handoff why the current owner has it, when it was handed over: "Covering for neha while
     *     she's on leave (2026-11-16 to 2026-11-27)" or "Taken over from neha, who left on 2026-11-21"
     */
    public record PromiseView(String id, String owner, String what, String due, String status, String createdAt,
            String latestQuote, List<String> dependsOn, List<String> blocks, String recipient, String handoff, String updatedAt,
            boolean canComplete) {}

    public record LinkView(String from, String to, String status, String reason, String fromWhat, String fromOwner,
            String toWhat, String toOwner, String decidedBy) {}

    public record NewLink(String from, String to, String by) {}

    public record LinkDecision(String from, String to, String decision, String by) {}

    public record NewAbsence(String person, String startsOn, String endsOn, String reason) {}

    public record Assignment(String promiseId, String owner, String by) {}

    /** @param plan "A", "B", "C" or "reject" */
    public record PlanVote(int round, String plan, String by, String reason) {}

    private static final EnumSet<PromiseStatus> OPEN = EnumSet.of(PromiseStatus.DETECTED, PromiseStatus.ACTIVE, PromiseStatus.AT_RISK);

    private final String tenant;
    private final Repository repo;
    private final LinkStore links;
    private final InboxStore inbox;
    private final HandoffStore handoffs;
    private final Directory directory;
    private final InboxService inboxService;
    private final HandoffScheduler scheduler;
    private final Signals signals;
    private final NegotiationStore negotiations;
    private final Visibility visibility;
    private final AuditLog audit;
    private final ObjectProvider<MailboxOwner> mailbox;
    private final AuthProperties auth;

    public ApiController(PromiseGraphProperties props, Repository repo, LinkStore links, InboxStore inbox, HandoffStore handoffs,
            Directory directory, InboxService inboxService, HandoffScheduler scheduler, Signals signals, NegotiationStore negotiations,
            Visibility visibility, AuditLog audit, ObjectProvider<MailboxOwner> mailbox, AuthProperties auth) {
        this.tenant = props.tenant();
        this.mailbox = mailbox;
        this.auth = auth;
        this.repo = repo;
        this.links = links;
        this.inbox = inbox;
        this.handoffs = handoffs;
        this.directory = directory;
        this.inboxService = inboxService;
        this.scheduler = scheduler;
        this.signals = signals;
        this.negotiations = negotiations;
        this.visibility = visibility;
        this.audit = audit;
    }

    // ------------------------------------------------------------ who is asking

    static String require(String viewer) {
        if (viewer == null || viewer.isBlank()) {
            throw new InboxService.ActionException(401, "say who is viewing with the " + VIEWER + " header");
        }
        return viewer.strip();
    }

    private void record(String actor, String action, String target, String detail) {
        audit.record(new AuditEntry(tenant, Instant.now().toString(), actor, action, target, detail));
    }

    private Set<String> visibleIds(String viewer) {
        return visibility.visibleIds(viewer, repo.promisesByStatus(tenant, EnumSet.allOf(PromiseStatus.class)), links.links(tenant),
                negotiations.list(tenant), handoffs.handoffs(tenant));
    }

    /** Changing a handoff is for the person away and their lead. */
    private void requirePersonOrLead(String viewer, String person) {
        if (!viewer.equals(person) && !visibility.isLeadOf(viewer, person) && !visibility.canOperate(viewer)) {
            throw new InboxService.ActionException(403, "only " + person + " or their lead can do that");
        }
    }

    // ------------------------------------------------------------ people

    /**
     * Who can be chosen as the viewer. With Gmail connected, that is only the connected account:
     * the app is personal, and the demo company's people don't belong in it.
     */
    @GetMapping("/people")
    public List<PersonView> people() {
        MailboxOwner owner = mailbox.getIfAvailable();
        if (owner != null) {
            String email = owner.email().orElseThrow(() -> new InboxService.ActionException(503,
                    "Gmail is connected but could not be reached to find your account; check the app log, "
                            + "or sign in again with gradlew gmailAuth"));
            return List.of(new PersonView(email, email, "", "", true));
        }
        if (auth.team()) {
            // Team mode: the People page, and nobody else (no addresses found in other people's mail).
            return directory.people().stream().map(p -> new PersonView(p.id(), p.name(), p.team(), p.role(), p.lead())).toList();
        }
        Map<String, PersonView> out = new LinkedHashMap<>();
        for (Person p : directory.people()) {
            out.put(p.id(), new PersonView(p.id(), p.name(), p.team(), p.role(), p.lead()));
        }
        TreeSet<String> others = new TreeSet<>(inbox.recipients(tenant));
        repo.promisesByStatus(tenant, EnumSet.allOf(PromiseStatus.class)).forEach(p -> others.add(p.owner()));
        for (String id : others) {
            out.putIfAbsent(id, new PersonView(id, id, "", "", false));
        }
        return new ArrayList<>(out.values());
    }

    // ------------------------------------------------------------ promises and links

    @GetMapping("/promises")
    public List<PromiseView> promises(@RequestHeader(name = VIEWER, required = false) String viewerHeader,
            @RequestParam(defaultValue = "false") boolean all) {
        String viewer = require(viewerHeader);
        Set<String> visible = visibleIds(viewer);
        List<Promise> ps = repo.promisesByStatus(tenant, all ? EnumSet.allOf(PromiseStatus.class) : OPEN).stream()
                .filter(p -> visible.contains(p.id())).toList();
        Map<String, List<String>> dependsOn = new HashMap<>();
        Map<String, List<String>> blocks = new HashMap<>();
        for (Link l : links.links(tenant)) {
            if (l.status() == Link.Status.CONFIRMED && visible.contains(l.from()) && visible.contains(l.to())) {
                dependsOn.computeIfAbsent(l.to(), k -> new ArrayList<>()).add(l.from());
                blocks.computeIfAbsent(l.from(), k -> new ArrayList<>()).add(l.to());
            }
        }
        Map<String, String> handedOver = handoffNotes();
        return ps.stream()
                .sorted(Comparator.comparing((Promise p) -> p.due() == null ? "9999" : p.due()).thenComparing(Promise::id))
                .map(p -> new PromiseView(p.id(), p.owner(), p.what(), p.due(), p.status().dbValue(), p.createdAt(),
                        p.evidence().isEmpty() || !visibility.canSeeQuotes(viewer, p) ? null : p.evidence().get(p.evidence().size() - 1).quote(),
                        dependsOn.getOrDefault(p.id(), List.of()), blocks.getOrDefault(p.id(), List.of()), p.recipient(),
                        handedOver.get(p.id() + "\u001f" + p.owner()), p.updatedAt(),
                        p.status().isOpen() && (p.owner().equals(viewer) || visibility.canOperate(viewer))))
                .toList();
    }

    /**
     * The owner says a promise is done. The promise workflow records it as kept (shown as
     * "completed") and finishes, so no more reminders are sent for it.
     */
    @PostMapping("/promises/{id}/done")
    public Map<String, String> done(@RequestHeader(name = VIEWER, required = false) String viewerHeader, @PathVariable String id) {
        String viewer = require(viewerHeader);
        Promise p = repo.getPromise(tenant, id).filter(x -> visibleIds(viewer).contains(x.id()))
                .orElseThrow(() -> new InboxService.ActionException(404, "no such promise"));
        if (!p.owner().equals(viewer) && !visibility.canOperate(viewer)) {
            throw new InboxService.ActionException(403, "only " + p.owner() + " can mark this promise done");
        }
        if (!p.status().isOpen()) {
            throw new InboxService.ActionException(409, "this promise is already closed (" + p.status().dbValue() + ")");
        }
        signals.toPromise(id, PromiseSignal.of("kept", "web:done:" + id, viewer));
        record(viewer, "promise:done", id, null);
        return Map.of("status", "completing");
    }

    /** promise ID + owner → why that owner has it, for promises moved by a handoff. */
    private Map<String, String> handoffNotes() {
        Map<String, String> out = new HashMap<>();
        for (HandoffRecord h : handoffs.handoffs(tenant)) {
            for (HandoffRecord.ItemState i : h.items()) {
                if (!"transferred".equals(i.state()) || i.acceptedOwner() == null) {
                    continue;
                }
                String note = h.endsOn() == null
                        ? "Taken over from " + h.person() + ", who left on " + h.startsOn()
                        : "Covering for " + h.person() + " while they're " + ("leave".equals(h.reason()) ? "on leave" : "away")
                                + " (" + h.startsOn() + " to " + h.endsOn() + "); it goes back to them afterwards";
                out.put(i.promiseId() + "\u001f" + i.acceptedOwner(), note);
            }
        }
        return out;
    }

    @GetMapping("/links")
    public List<LinkView> links(@RequestHeader(name = VIEWER, required = false) String viewerHeader) {
        Set<String> visible = visibleIds(require(viewerHeader));
        List<LinkView> out = new ArrayList<>();
        for (Link l : links.links(tenant)) {
            if (!visible.contains(l.from()) || !visible.contains(l.to())) {
                continue;
            }
            Promise f = repo.getPromise(tenant, l.from()).orElse(null);
            Promise t = repo.getPromise(tenant, l.to()).orElse(null);
            out.add(new LinkView(l.from(), l.to(), l.status().dbValue(), l.reason(),
                    f == null ? null : f.what(), f == null ? null : f.owner(),
                    t == null ? null : t.what(), t == null ? null : t.owner(), l.decidedBy()));
        }
        return out;
    }

    @PostMapping("/links")
    public ResponseEntity<Map<String, String>> addLink(@RequestHeader(name = VIEWER, required = false) String viewerHeader,
            @RequestBody NewLink req) {
        String viewer = require(viewerHeader);
        if (req.from() == null || req.to() == null || req.from().equals(req.to())) {
            throw new IllegalArgumentException("a link needs two different promises");
        }
        Set<String> visible = visibleIds(viewer);
        if (!visible.contains(req.from()) || !visible.contains(req.to())) {
            throw new InboxService.ActionException(404, "unknown promise");
        }
        Promise to = repo.getPromise(tenant, req.to()).orElseThrow(() -> new InboxService.ActionException(404, "unknown promise"));
        requirePersonOrLead(viewer, to.owner()); // the dependent promise's owner says what it waits on
        boolean created = links.addLink(new Link(tenant, req.from(), req.to(), Link.Status.CONFIRMED, "added by hand", viewer, viewer,
                Instant.now().toString()));
        if (!created) {
            links.decide(tenant, req.from(), req.to(), Link.Status.CONFIRMED, viewer);
        }
        record(viewer, "link:add", req.from() + ">" + req.to(), null);
        return ResponseEntity.status(created ? HttpStatus.CREATED : HttpStatus.OK).body(Map.of("status", "confirmed"));
    }

    @PostMapping("/links/decision")
    public Map<String, String> decideLink(@RequestHeader(name = VIEWER, required = false) String viewerHeader,
            @RequestBody LinkDecision req) {
        String viewer = require(viewerHeader);
        Link.Status status = switch (req.decision() == null ? "" : req.decision()) {
            case "confirm" -> Link.Status.CONFIRMED;
            case "reject" -> Link.Status.REJECTED;
            default -> throw new IllegalArgumentException("decision is confirm or reject");
        };
        Promise to = repo.getPromise(tenant, req.to() == null ? "" : req.to())
                .orElseThrow(() -> new InboxService.ActionException(404, "no such link"));
        requirePersonOrLead(viewer, to.owner());
        if (!links.decide(tenant, req.from(), req.to(), status, viewer)) {
            throw new InboxService.ActionException(404, "no such link");
        }
        record(viewer, "link:" + req.decision(), req.from() + ">" + req.to(), null);
        return Map.of("status", status.dbValue());
    }

    // ------------------------------------------------------------ inbox

    @GetMapping("/inbox")
    public List<InboxItem> inbox(@RequestHeader(name = VIEWER, required = false) String viewerHeader, @RequestParam String person,
            @RequestParam(defaultValue = "false") boolean all) {
        if (!require(viewerHeader).equals(person)) {
            throw new InboxService.ActionException(403, "an inbox is only shown to its owner");
        }
        return inbox.inbox(tenant, person, !all);
    }

    @PostMapping("/inbox/{key}/actions")
    public InboxItem act(@RequestHeader(name = VIEWER, required = false) String viewerHeader, @PathVariable String key,
            @RequestBody InboxService.ActionRequest req) {
        String viewer = require(viewerHeader);
        InboxItem item = inbox.getItem(tenant, key).orElseThrow(() -> new InboxService.ActionException(404, "no such inbox item"));
        if (!item.recipient().equals(viewer)) {
            throw new InboxService.ActionException(403, "only " + item.recipient() + " can answer this");
        }
        InboxItem done = inboxService.act(tenant, key, new InboxService.ActionRequest(req.action(), viewer, req.due(), req.what()));
        record(viewer, "inbox:" + req.action(), item.subject() != null ? item.subject() : item.promiseId(), req.due());
        return done;
    }

    // ------------------------------------------------------------ absences and handoffs

    @GetMapping("/handoffs")
    public List<HandoffRecord> handoffs(@RequestHeader(name = VIEWER, required = false) String viewerHeader) {
        String viewer = require(viewerHeader);
        return handoffs.handoffs(tenant).stream().map(h -> visibility.view(viewer, h)).flatMap(java.util.Optional::stream).toList();
    }

    @PostMapping("/absences")
    public ResponseEntity<Map<String, String>> addAbsence(@RequestHeader(name = VIEWER, required = false) String viewerHeader,
            @RequestBody NewAbsence req) {
        String viewer = require(viewerHeader);
        if (req.person() == null || req.person().isBlank() || req.startsOn() == null) {
            throw new IllegalArgumentException("person and startsOn are required");
        }
        requirePersonOrLead(viewer, req.person().strip());
        Absence a;
        try {
            a = new Absence(tenant, req.person().strip(), req.startsOn(), req.endsOn() == null || req.endsOn().isBlank() ? null : req.endsOn(),
                    req.reason() == null || req.reason().isBlank() ? "leave" : req.reason(), "manual");
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("dates look like 2026-11-20");
        }
        boolean started = scheduler.schedule(a);
        record(viewer, "absence:add", a.handoffId(), a.startsOn() + " to " + a.endsOn());
        return ResponseEntity.status(started ? HttpStatus.CREATED : HttpStatus.OK)
                .body(Map.of("handoff", a.handoffId(), "status", started ? "scheduled" : "already scheduled or in the past"));
    }

    private HandoffRecord handoff(String id) {
        return handoffs.getHandoff(tenant, id).orElseThrow(() -> new InboxService.ActionException(404, "no such handoff"));
    }

    @PostMapping("/handoffs/{id}/assign")
    public Map<String, String> assign(@RequestHeader(name = VIEWER, required = false) String viewerHeader, @PathVariable String id,
            @RequestBody Assignment req) {
        String viewer = require(viewerHeader);
        if (req.promiseId() == null || req.owner() == null || req.owner().isBlank()) {
            throw new IllegalArgumentException("promiseId and owner are required");
        }
        requirePersonOrLead(viewer, handoff(id).person());
        signals.toHandoff(id, new HandoffResponse("assign", req.promiseId(), viewer, req.owner().strip(),
                "web:" + req.promiseId() + ":" + req.owner().strip()));
        record(viewer, "handoff:assign", id + ":" + req.promiseId(), req.owner().strip());
        return Map.of("status", "asked " + req.owner().strip());
    }

    /** A departure's handover plan, approved by the leaving person's lead (the workflow checks it's them). */
    @PostMapping("/handoffs/{id}/approve")
    public Map<String, String> approveHandoff(@RequestHeader(name = VIEWER, required = false) String viewerHeader, @PathVariable String id) {
        String viewer = require(viewerHeader);
        requirePersonOrLead(viewer, handoff(id).person());
        signals.toHandoff(id, new HandoffResponse("approve plan", "plan", viewer, null, "web:approve"));
        record(viewer, "handoff:approve", id, null);
        return Map.of("status", "approved");
    }

    @PostMapping("/handoffs/{id}/cancel")
    public Map<String, String> cancel(@RequestHeader(name = VIEWER, required = false) String viewerHeader, @PathVariable String id) {
        String viewer = require(viewerHeader);
        requirePersonOrLead(viewer, handoff(id).person());
        signals.toHandoff(id, new HandoffResponse("cancel", null, viewer, null, "web:cancel"));
        record(viewer, "handoff:cancel", id, null);
        return Map.of("status", "cancelling");
    }

    // ------------------------------------------------------------ negotiations

    @GetMapping("/negotiations")
    public List<NegotiationRecord> negotiations(@RequestHeader(name = VIEWER, required = false) String viewerHeader) {
        String viewer = require(viewerHeader);
        return negotiations.list(tenant).stream().filter(n -> visibility.canSee(viewer, n) || visibility.canOperate(viewer)).toList();
    }

    @GetMapping("/negotiations/{id}")
    public NegotiationRecord negotiation(@RequestHeader(name = VIEWER, required = false) String viewerHeader, @PathVariable String id) {
        String viewer = require(viewerHeader);
        NegotiationRecord n = negotiations.get(tenant, id).orElseThrow(() -> new InboxService.ActionException(404, "no such negotiation"));
        if (!visibility.canSee(viewer, n) && !visibility.canOperate(viewer)) {
            throw new InboxService.ActionException(404, "no such negotiation");
        }
        return n;
    }

    /** An approver's choice from the Plans page. The workflow ignores votes for old rounds. */
    @PostMapping("/negotiations/{id}/vote")
    public Map<String, String> vote(@RequestHeader(name = VIEWER, required = false) String viewerHeader, @PathVariable String id,
            @RequestBody PlanVote req) {
        String viewer = require(viewerHeader);
        if (req.plan() == null || req.plan().isBlank()) {
            throw new IllegalArgumentException("plan is required");
        }
        NegotiationRecord n = negotiation(viewer, id);
        if (!n.approvers().contains(viewer)) {
            throw new InboxService.ActionException(403, viewer + " is not an approver of this negotiation");
        }
        String plan = req.plan().strip();
        boolean reject = plan.equalsIgnoreCase("reject");
        if (!reject && n.plan(plan).isEmpty()) {
            throw new IllegalArgumentException("this round offers plans " + n.plans().stream().map(p -> p.id()).toList());
        }
        signals.toNegotiation(id, new Vote(reject ? "reject" : "approve", req.round(), reject ? null : plan, viewer, req.reason(),
                "web:" + req.round() + ":" + viewer + ":" + plan));
        record(viewer, "negotiation:" + (reject ? "reject" : "approve " + plan), id, req.reason());
        return Map.of("status", reject ? "rejected" : "chose plan " + plan);
    }

    @PostMapping("/negotiations/{id}/cancel")
    public Map<String, String> cancelNegotiation(@RequestHeader(name = VIEWER, required = false) String viewerHeader,
            @PathVariable String id, @RequestBody(required = false) Map<String, String> body) {
        String viewer = require(viewerHeader);
        NegotiationRecord n = negotiation(viewer, id);
        if (!n.approvers().contains(viewer) && !visibility.canOperate(viewer)) {
            throw new InboxService.ActionException(403, "only an approver can cancel a negotiation");
        }
        String reason = body == null ? null : body.get("reason");
        signals.toNegotiation(id, Vote.stop("cancel", viewer, reason, "web:cancel"));
        record(viewer, "negotiation:cancel", id, reason);
        return Map.of("status", "cancelling");
    }

    // ------------------------------------------------------------ errors

    @ExceptionHandler(InboxService.ActionException.class)
    public ResponseEntity<Map<String, String>> actionError(InboxService.ActionException e) {
        return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(Signals.ClosedException.class)
    public ResponseEntity<Map<String, String>> closed(Signals.ClosedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
