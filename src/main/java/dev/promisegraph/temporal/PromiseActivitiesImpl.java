package dev.promisegraph.temporal;

import static dev.promisegraph.config.PromiseGraphProperties.TASK_QUEUE_MAIN;

import dev.promisegraph.domain.Commitment;
import dev.promisegraph.domain.CommitmentKind;
import dev.promisegraph.domain.Ids;
import dev.promisegraph.domain.Matching;
import dev.promisegraph.domain.Matching.Resolution;
import dev.promisegraph.domain.Message;
import dev.promisegraph.domain.Promise;
import dev.promisegraph.domain.PromiseEvent;
import dev.promisegraph.domain.PromiseStatus;
import dev.promisegraph.domain.TimingPolicy;
import dev.promisegraph.extraction.Detect;
import dev.promisegraph.handoff.Absence;
import dev.promisegraph.handoff.AbsenceDetector;
import dev.promisegraph.ingestion.ExtractionInput;
import dev.promisegraph.links.Link;
import dev.promisegraph.links.LinkSuggester;
import dev.promisegraph.notify.Notification;
import dev.promisegraph.notify.Notifier;
import dev.promisegraph.store.LinkStore;
import dev.promisegraph.store.Repository;
import dev.promisegraph.temporal.Contracts.Action;
import dev.promisegraph.temporal.Contracts.ClaimResult;
import dev.promisegraph.temporal.Contracts.DigestRequest;
import dev.promisegraph.temporal.Contracts.DispatchRequest;
import dev.promisegraph.temporal.Contracts.PromiseSignal;
import dev.promisegraph.temporal.Contracts.PromiseWorkflowInput;
import dev.promisegraph.temporal.Contracts.RecordRequest;
import dev.promisegraph.temporal.Contracts.ResolveRequest;
import io.micrometer.core.instrument.Metrics;
import io.temporal.activity.Activity;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowOptions;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Every step that touches the outside world. Each is safe to retry: database writes are
 * idempotent, notifications are deduplicated by key, and workflow starts tolerate duplicates.
 */
public final class PromiseActivitiesImpl implements PromiseActivities {

    /** Referenced by name so activities do not depend on the workflow implementation. */
    static final String PROMISE_WORKFLOW = "PromiseWorkflow";
    static final String PROMISE_SIGNAL = "event";

    private static final Logger log = LoggerFactory.getLogger(PromiseActivitiesImpl.class);

    private final Repository repo;
    private final LinkStore links;
    private final Notifier notifier;
    private final TimingPolicy policy;
    private final double confidenceThreshold;
    private final WorkflowClient client;

    public PromiseActivitiesImpl(Repository repo, LinkStore links, Notifier notifier, TimingPolicy policy,
            double confidenceThreshold, WorkflowClient client) {
        this.repo = repo;
        this.links = links;
        this.notifier = notifier;
        this.policy = policy;
        this.confidenceThreshold = confidenceThreshold;
        this.client = client;
    }

    @Override
    public ClaimResult claimMessages(ExtractionInput input) {
        String claimant = Activity.getExecutionContext().getInfo().getWorkflowId();
        List<Message> kept = new ArrayList<>();
        List<Absence> absences = new ArrayList<>();
        for (Message m : input.messages()) {
            if (!repo.claimMessage(input.tenant(), m.id(), claimant)) {
                continue;
            }
            if (Detect.looksLikeCommitment(m.text())) {
                kept.add(m);
            }
            AbsenceDetector.detect(m).ifPresent(absences::add);
        }
        return new ClaimResult(kept, absences);
    }

    @Override
    public List<Action> resolveCommitments(ResolveRequest req) {
        Set<String> owners = new TreeSet<>();
        req.commitments().forEach(c -> owners.add(c.owner()));
        List<Promise> open = new ArrayList<>(owners.isEmpty() ? List.of() : repo.openPromises(req.tenant(), owners));
        List<String> candidateIds = req.commitments().stream()
                .filter(c -> c.kind() == CommitmentKind.NEW)
                .map(c -> Ids.promiseId(req.tenant(), c.messageId(), c.owner(), c.what()))
                .toList();
        Set<String> existing = new HashSet<>(repo.existingIds(req.tenant(), candidateIds));

        List<Action> actions = new ArrayList<>();
        List<Promise> linkCandidates = null; // every open promise, loaded only if a dependency phrase appears
        for (Commitment c : req.commitments()) {
            Resolution r = Matching.resolve(c, open, req.tenant(), req.now(), existing);
            switch (r.action()) {
                case START -> {
                    if (r.promise() != null) {
                        repo.createPromise(r.promise());
                        open.add(r.promise());
                        existing.add(r.promise().id());
                        if (linkCandidates == null && LinkSuggester.hasDependencyPhrase(c.quote())) {
                            linkCandidates = new ArrayList<>(repo.promisesByStatus(req.tenant(), OPEN));
                        }
                        if (linkCandidates != null) {
                            suggestLink(req, r.promise(), c.quote(), linkCandidates);
                            linkCandidates.add(r.promise());
                        }
                    }
                    Promise p = r.promise() != null ? r.promise() : repo.getPromise(req.tenant(), r.promiseId()).orElse(null);
                    if (p != null) {
                        actions.add(new Action("start", p.id(), p, null));
                    }
                }
                case UPDATE, SLIP, KEPT -> {
                    String kind = r.action().name().toLowerCase(Locale.ROOT);
                    String due = r.action() == Matching.Action.SLIP ? r.due() : c.due();
                    actions.add(new Action("signal", r.promiseId(), null,
                            new PromiseSignal(kind, c.messageId(), c.owner(), due, null, c.quote(), c.channel(), null)));
                }
                case IGNORE -> log.info("ignored {} from {}: {}", c.kind(), c.messageId(), r.reason());
            }
        }
        return actions;
    }

    private static final EnumSet<PromiseStatus> OPEN = EnumSet.of(PromiseStatus.DETECTED, PromiseStatus.ACTIVE, PromiseStatus.AT_RISK);

    /** Records a suggested dependency and asks the new promise's owner to confirm it. */
    private void suggestLink(ResolveRequest req, Promise promise, String quote, List<Promise> candidates) {
        LinkSuggester.suggest(promise, quote, candidates).ifPresent(s -> {
            if (links.getLink(req.tenant(), s.upstreamId(), promise.id()).isPresent()) {
                return; // already suggested, confirmed or rejected
            }
            links.addLink(new Link(req.tenant(), s.upstreamId(), promise.id(), Link.Status.SUGGESTED,
                    s.clause(), "rules", null, req.now()));
            Promise upstream = candidates.stream().filter(p -> p.id().equals(s.upstreamId())).findFirst().orElseThrow();
            String text = "Does \"" + promise.what() + "\" depend on " + upstream.owner() + "'s promise to "
                    + upstream.what() + (upstream.due() == null ? "" : " (due " + upstream.due() + ")")
                    + "? You wrote: \"" + s.clause() + "\". If it does, I'll warn you when that promise slips.";
            sendNotification(new Notification(req.tenant(), promise.owner(), text,
                    Ids.notificationKey(req.tenant(), "link", s.upstreamId(), promise.id()),
                    List.of("confirm link", "not related"), promise.id(), "link:" + s.upstreamId() + ">" + promise.id()));
        });
    }

    @Override
    public int dispatchActions(DispatchRequest req) {
        int done = 0;
        for (Action a : req.actions()) {
            String workflowId = Ids.promiseWorkflowId(a.promiseId());
            if ("start".equals(a.type()) && a.promise() != null) {
                WorkflowOptions options = WorkflowOptions.newBuilder()
                        .setWorkflowId(workflowId)
                        .setTaskQueue(TASK_QUEUE_MAIN)
                        .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                        .build();
                try {
                    client.newUntypedWorkflowStub(PROMISE_WORKFLOW, options)
                            .start(PromiseWorkflowInput.fresh(a.promise(), policy, confidenceThreshold));
                } catch (WorkflowExecutionAlreadyStarted e) {
                    // an earlier attempt started it
                }
                done++;
            } else if ("signal".equals(a.type()) && a.signal() != null) {
                try {
                    client.newUntypedWorkflowStub(workflowId).signal(PROMISE_SIGNAL, a.signal());
                    done++;
                } catch (WorkflowNotFoundException e) {
                    log.warn("promise workflow {} is closed; dropped {}", workflowId, a.signal().kind());
                }
            }
        }
        return done;
    }

    @Override
    public void recordPromise(RecordRequest req) {
        Promise p = req.promise();
        repo.savePromise(p);
        if (repo.appendEvent(new PromiseEvent(p.tenant(), p.id(), req.eventKind(), req.actor(), p.updatedAt(),
                p.id() + ":" + p.version(), req.detail()))) {
            Metrics.counter("promisegraph.promise.events", "kind", req.eventKind()).increment();
        }
    }

    /** A crash between sending and recording can repeat one message; we accept that over losing it. */
    @Override
    public boolean startRipple(String workflowId, Contracts.RippleRequest request) {
        WorkflowOptions options = WorkflowOptions.newBuilder()
                .setWorkflowId(workflowId)
                .setTaskQueue(TASK_QUEUE_MAIN)
                .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                .build();
        try {
            WorkflowClient.start(client.newWorkflowStub(RippleWorkflow.class, options)::run, request);
            return true;
        } catch (WorkflowExecutionAlreadyStarted e) {
            return false; // a retry: the ripple for this change is already running or done
        }
    }

    @Override
    public boolean sendNotification(Notification n) {
        if (repo.notificationSent(n.tenant(), n.key())) {
            return false;
        }
        notifier.send(n);
        repo.recordNotification(n.tenant(), n.key(), n.recipient());
        Metrics.counter("promisegraph.notifications.sent", "actionable", String.valueOf(!n.actions().isEmpty())).increment();
        return true;
    }

    @Override
    public int sendDigests(DigestRequest req) {
        OffsetDateTime now = OffsetDateTime.parse(req.now());
        LocalDate horizon = now.toLocalDate().plusDays(req.horizonDays());
        var statuses = EnumSet.of(PromiseStatus.DETECTED, PromiseStatus.ACTIVE, PromiseStatus.AT_RISK);
        Map<String, List<String>> byOwner = new TreeMap<>();
        List<Promise> promises = new ArrayList<>(repo.promisesByStatus(req.tenant(), statuses));
        promises.sort(Comparator.comparing(p -> p.due() == null ? "9999" : p.due()));
        for (Promise p : promises) {
            String line = null;
            if (p.status() == PromiseStatus.AT_RISK) {
                line = "At risk: " + p.what() + (p.due() != null ? " (due " + p.due() + ")" : "");
            } else if (p.status() == PromiseStatus.DETECTED && p.confidence() < confidenceThreshold) {
                line = "Please confirm: " + p.what();
            } else if (p.due() != null && !LocalDate.parse(p.due()).isAfter(horizon)) {
                line = "Due " + p.due() + ": " + p.what();
            }
            if (line != null) {
                byOwner.computeIfAbsent(p.owner(), k -> new ArrayList<>()).add(line);
            }
        }
        int sent = 0;
        for (var entry : byOwner.entrySet()) {
            String text = "Your promises today:\n- " + String.join("\n- ", entry.getValue());
            String key = Ids.notificationKey(req.tenant(), "digest", entry.getKey(), now.toLocalDate().toString());
            if (sendNotification(Notification.about(req.tenant(), entry.getKey(), text, key, List.of(), null))) {
                sent++;
            }
        }
        return sent;
    }
}
