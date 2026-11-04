package dev.promisegraph.web;

import dev.promisegraph.config.PromiseGraphProperties;
import dev.promisegraph.domain.Person;
import dev.promisegraph.domain.Promise;
import dev.promisegraph.domain.PromiseStatus;
import dev.promisegraph.handoff.Absence;
import dev.promisegraph.handoff.Directory;
import dev.promisegraph.handoff.HandoffRecord;
import dev.promisegraph.handoff.HandoffScheduler;
import dev.promisegraph.inbox.InboxItem;
import dev.promisegraph.inbox.InboxService;
import dev.promisegraph.inbox.Signals;
import dev.promisegraph.links.Link;
import dev.promisegraph.recovery.NegotiationRecord;
import dev.promisegraph.store.HandoffStore;
import dev.promisegraph.store.InboxStore;
import dev.promisegraph.store.LinkStore;
import dev.promisegraph.store.NegotiationStore;
import dev.promisegraph.store.Repository;
import dev.promisegraph.temporal.Contracts.HandoffResponse;
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
import java.util.TreeSet;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The JSON API behind the web app. It has no login yet, so the server listens on localhost only
 * (see {@code server.address}); add authentication before exposing it.
 */
@RestController
@RequestMapping("/api")
public class ApiController {

    public record PersonView(String id, String name, String team, String role) {}

    public record PromiseView(String id, String owner, String what, String due, String status, String createdAt,
            String latestQuote, List<String> dependsOn, List<String> blocks) {}

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

    public ApiController(PromiseGraphProperties props, Repository repo, LinkStore links, InboxStore inbox, HandoffStore handoffs,
            Directory directory, InboxService inboxService, HandoffScheduler scheduler, Signals signals, NegotiationStore negotiations) {
        this.tenant = props.tenant();
        this.repo = repo;
        this.links = links;
        this.inbox = inbox;
        this.handoffs = handoffs;
        this.directory = directory;
        this.inboxService = inboxService;
        this.scheduler = scheduler;
        this.signals = signals;
        this.negotiations = negotiations;
    }

    // ------------------------------------------------------------ people

    @GetMapping("/people")
    public List<PersonView> people() {
        Map<String, PersonView> out = new LinkedHashMap<>();
        for (Person p : directory.people()) {
            out.put(p.id(), new PersonView(p.id(), p.name(), p.team(), p.role()));
        }
        TreeSet<String> others = new TreeSet<>(inbox.recipients(tenant));
        repo.promisesByStatus(tenant, EnumSet.allOf(PromiseStatus.class)).forEach(p -> others.add(p.owner()));
        for (String id : others) {
            out.putIfAbsent(id, new PersonView(id, id, "", ""));
        }
        return new ArrayList<>(out.values());
    }

    // ------------------------------------------------------------ promises and links

    @GetMapping("/promises")
    public List<PromiseView> promises(@RequestParam(defaultValue = "false") boolean all) {
        List<Promise> ps = repo.promisesByStatus(tenant, all ? EnumSet.allOf(PromiseStatus.class) : OPEN);
        Map<String, List<String>> dependsOn = new HashMap<>();
        Map<String, List<String>> blocks = new HashMap<>();
        for (Link l : links.links(tenant)) {
            if (l.status() == Link.Status.CONFIRMED) {
                dependsOn.computeIfAbsent(l.to(), k -> new ArrayList<>()).add(l.from());
                blocks.computeIfAbsent(l.from(), k -> new ArrayList<>()).add(l.to());
            }
        }
        return ps.stream()
                .sorted(Comparator.comparing((Promise p) -> p.due() == null ? "9999" : p.due()).thenComparing(Promise::id))
                .map(p -> new PromiseView(p.id(), p.owner(), p.what(), p.due(), p.status().dbValue(), p.createdAt(),
                        p.evidence().isEmpty() ? null : p.evidence().get(p.evidence().size() - 1).quote(),
                        dependsOn.getOrDefault(p.id(), List.of()), blocks.getOrDefault(p.id(), List.of())))
                .toList();
    }

    @GetMapping("/links")
    public List<LinkView> links() {
        List<LinkView> out = new ArrayList<>();
        for (Link l : links.links(tenant)) {
            Promise f = repo.getPromise(tenant, l.from()).orElse(null);
            Promise t = repo.getPromise(tenant, l.to()).orElse(null);
            out.add(new LinkView(l.from(), l.to(), l.status().dbValue(), l.reason(),
                    f == null ? null : f.what(), f == null ? null : f.owner(),
                    t == null ? null : t.what(), t == null ? null : t.owner(), l.decidedBy()));
        }
        return out;
    }

    @PostMapping("/links")
    public ResponseEntity<Map<String, String>> addLink(@RequestBody NewLink req) {
        if (req.from() == null || req.to() == null || req.from().equals(req.to())) {
            throw new IllegalArgumentException("a link needs two different promises");
        }
        if (repo.getPromise(tenant, req.from()).isEmpty() || repo.getPromise(tenant, req.to()).isEmpty()) {
            throw new IllegalArgumentException("unknown promise");
        }
        String by = req.by() == null ? "web" : req.by();
        boolean created = links.addLink(new Link(tenant, req.from(), req.to(), Link.Status.CONFIRMED, "added by hand", by, by,
                Instant.now().toString()));
        if (!created) {
            links.decide(tenant, req.from(), req.to(), Link.Status.CONFIRMED, by);
        }
        return ResponseEntity.status(created ? HttpStatus.CREATED : HttpStatus.OK).body(Map.of("status", "confirmed"));
    }

    @PostMapping("/links/decision")
    public Map<String, String> decideLink(@RequestBody LinkDecision req) {
        Link.Status status = switch (req.decision() == null ? "" : req.decision()) {
            case "confirm" -> Link.Status.CONFIRMED;
            case "reject" -> Link.Status.REJECTED;
            default -> throw new IllegalArgumentException("decision is confirm or reject");
        };
        if (!links.decide(tenant, req.from(), req.to(), status, req.by() == null ? "web" : req.by())) {
            throw new InboxService.ActionException(404, "no such link");
        }
        return Map.of("status", status.dbValue());
    }

    // ------------------------------------------------------------ inbox

    @GetMapping("/inbox")
    public List<InboxItem> inbox(@RequestParam String person, @RequestParam(defaultValue = "false") boolean all) {
        return inbox.inbox(tenant, person, !all);
    }

    @PostMapping("/inbox/{key}/actions")
    public InboxItem act(@PathVariable String key, @RequestBody InboxService.ActionRequest req) {
        return inboxService.act(tenant, key, req);
    }

    // ------------------------------------------------------------ absences and handoffs

    @GetMapping("/handoffs")
    public List<HandoffRecord> handoffs() {
        return handoffs.handoffs(tenant);
    }

    @PostMapping("/absences")
    public ResponseEntity<Map<String, String>> addAbsence(@RequestBody NewAbsence req) {
        if (req.person() == null || req.person().isBlank() || req.startsOn() == null) {
            throw new IllegalArgumentException("person and startsOn are required");
        }
        Absence a;
        try {
            a = new Absence(tenant, req.person().strip(), req.startsOn(), req.endsOn() == null || req.endsOn().isBlank() ? null : req.endsOn(),
                    req.reason() == null || req.reason().isBlank() ? "leave" : req.reason(), "manual");
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("dates look like 2026-11-20");
        }
        boolean started = scheduler.schedule(a);
        return ResponseEntity.status(started ? HttpStatus.CREATED : HttpStatus.OK)
                .body(Map.of("handoff", a.handoffId(), "status", started ? "scheduled" : "already scheduled or in the past"));
    }

    @PostMapping("/handoffs/{id}/assign")
    public Map<String, String> assign(@PathVariable String id, @RequestBody Assignment req) {
        if (req.promiseId() == null || req.owner() == null || req.owner().isBlank()) {
            throw new IllegalArgumentException("promiseId and owner are required");
        }
        signals.toHandoff(id, new HandoffResponse("assign", req.promiseId(), req.by() == null ? "web" : req.by(), req.owner().strip(),
                "web:" + req.promiseId() + ":" + req.owner().strip()));
        return Map.of("status", "asked " + req.owner().strip());
    }

    @PostMapping("/handoffs/{id}/cancel")
    public Map<String, String> cancel(@PathVariable String id) {
        signals.toHandoff(id, new HandoffResponse("cancel", null, "web", null, "web:cancel"));
        return Map.of("status", "cancelling");
    }

    // ------------------------------------------------------------ negotiations

    @GetMapping("/negotiations")
    public List<NegotiationRecord> negotiations() {
        return negotiations.list(tenant);
    }

    @GetMapping("/negotiations/{id}")
    public NegotiationRecord negotiation(@PathVariable String id) {
        return negotiations.get(tenant, id).orElseThrow(() -> new InboxService.ActionException(404, "no such negotiation"));
    }

    /** An approver's choice from the Plans page. The workflow ignores votes from non-approvers and for old rounds. */
    @PostMapping("/negotiations/{id}/vote")
    public Map<String, String> vote(@PathVariable String id, @RequestBody PlanVote req) {
        if (req.by() == null || req.by().isBlank() || req.plan() == null || req.plan().isBlank()) {
            throw new IllegalArgumentException("by and plan are required");
        }
        NegotiationRecord n = negotiation(id);
        if (!n.approvers().contains(req.by().strip())) {
            throw new InboxService.ActionException(403, req.by().strip() + " is not an approver of this negotiation");
        }
        String plan = req.plan().strip();
        boolean reject = plan.equalsIgnoreCase("reject");
        if (!reject && n.plan(plan).isEmpty()) {
            throw new IllegalArgumentException("this round offers plans " + n.plans().stream().map(p -> p.id()).toList());
        }
        String by = req.by().strip();
        signals.toNegotiation(id, new Vote(reject ? "reject" : "approve", req.round(), reject ? null : plan, by, req.reason(),
                "web:" + req.round() + ":" + by + ":" + plan));
        return Map.of("status", reject ? "rejected" : "chose plan " + plan);
    }

    @PostMapping("/negotiations/{id}/cancel")
    public Map<String, String> cancelNegotiation(@PathVariable String id, @RequestBody(required = false) Map<String, String> body) {
        String by = body == null || body.get("by") == null ? "web" : body.get("by");
        signals.toNegotiation(id, Vote.stop("cancel", by, body == null ? null : body.get("reason"), "web:cancel"));
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
