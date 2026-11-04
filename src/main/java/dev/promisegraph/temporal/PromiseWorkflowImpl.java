package dev.promisegraph.temporal;

import static dev.promisegraph.config.PromiseGraphProperties.TASK_QUEUE_MAIN;
import static dev.promisegraph.domain.PromiseStatus.ACTIVE;
import static dev.promisegraph.domain.PromiseStatus.AT_RISK;
import static dev.promisegraph.domain.PromiseStatus.CANCELLED;
import static dev.promisegraph.domain.PromiseStatus.DETECTED;
import static dev.promisegraph.domain.PromiseStatus.DISMISSED;
import static dev.promisegraph.domain.PromiseStatus.KEPT;

import dev.promisegraph.domain.Evidence;
import dev.promisegraph.domain.Ids;
import dev.promisegraph.domain.Lifecycle;
import dev.promisegraph.domain.Promise;
import dev.promisegraph.domain.PromiseStatus;
import dev.promisegraph.domain.Timing;
import dev.promisegraph.domain.TimingPolicy;
import dev.promisegraph.notify.Notification;
import dev.promisegraph.temporal.Contracts.PromiseSignal;
import dev.promisegraph.temporal.Contracts.PromiseWorkflowInput;
import dev.promisegraph.temporal.Contracts.RecordRequest;
import dev.promisegraph.temporal.Contracts.RippleRequest;
import io.temporal.api.enums.v1.ParentClosePolicy;
import io.temporal.workflow.Async;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;

/**
 * Deterministic code only: no I/O, no clocks other than {@link Workflow#currentTimeMillis()}.
 * Every side effect goes through an activity.
 */
public class PromiseWorkflowImpl implements PromiseWorkflow {

    static final int MAX_EVENTS_BEFORE_CONTINUE = 200;
    static final int MAX_REMEMBERED_SIGNALS = 500;

    private static final Logger log = Workflow.getLogger(PromiseWorkflowImpl.class);
    private static final String NUDGE_KEY = "nudge:missing-date";
    private static final String REMINDER_PREFIX = "reminder:";
    /** Phase 2 added ripple on slips; histories recorded before it replay without it. */
    static final String RIPPLE_CHANGE = "ripple-on-slip";

    private final PromiseActivities io = Workflow.newActivityStub(PromiseActivities.class, RetryPolicies.io(Duration.ofSeconds(30)));

    private final Deque<PromiseSignal> inbox = new ArrayDeque<>();
    private final List<String> applied = new ArrayList<>();
    private final Set<String> appliedSet = new HashSet<>();
    private final Set<String> sent = new HashSet<>();
    private Promise promise;
    private TimingPolicy policy;
    private double threshold;
    private int events;

    private record Deadline(Instant at, Runnable action) {}

    // ------------------------------------------------------------ interface

    @Override
    public void event(PromiseSignal signal) {
        inbox.addLast(signal);
    }

    @Override
    public Promise state() {
        return promise;
    }

    // ------------------------------------------------------------ main loop

    @Override
    public PromiseStatus run(PromiseWorkflowInput input) {
        promise = input.promise();
        policy = input.policy();
        threshold = input.confidenceThreshold();
        applied.addAll(input.applied());
        appliedSet.addAll(input.applied());
        sent.addAll(input.sentKeys());

        if (!input.started()) {
            record("tracking_started", "extractor", Map.of("confidence", "%.2f".formatted(promise.confidence())));
            announce();
        }

        while (!Lifecycle.isTerminal(promise.status())) {
            if (events >= MAX_EVENTS_BEFORE_CONTINUE && inbox.isEmpty()) {
                Workflow.continueAsNew(carryOver());
            }

            Optional<Deadline> deadline = nextDeadline();
            boolean timedOut = false;
            if (inbox.isEmpty()) {
                if (deadline.isEmpty()) {
                    Workflow.await(() -> !inbox.isEmpty());
                } else {
                    Duration remaining = Duration.between(now(), deadline.get().at());
                    timedOut = remaining.isNegative() || remaining.isZero()
                            || !Workflow.await(remaining, () -> !inbox.isEmpty());
                }
            }

            if (!inbox.isEmpty()) {
                while (!inbox.isEmpty() && !Lifecycle.isTerminal(promise.status())) {
                    apply(inbox.pollFirst());
                }
                if (Lifecycle.isTerminal(promise.status())) {
                    inbox.clear(); // nothing can change a closed promise
                }
            } else if (timedOut) {
                deadline.get().action().run();
            }
        }
        return promise.status();
    }

    private PromiseWorkflowInput carryOver() {
        List<String> recent = applied.subList(Math.max(0, applied.size() - MAX_REMEMBERED_SIGNALS), applied.size());
        return new PromiseWorkflowInput(promise, policy, threshold, true, new ArrayList<>(new TreeSet<>(sent)), new ArrayList<>(recent));
    }

    private static Instant now() {
        return Instant.ofEpochMilli(Workflow.currentTimeMillis());
    }

    // ------------------------------------------------------------ timers

    private Optional<Deadline> nextDeadline() {
        Instant created = OffsetDateTime.parse(promise.createdAt()).toInstant();
        PromiseStatus status = promise.status();

        if (status == DETECTED) {
            if (promise.confidence() >= threshold) {
                return Optional.of(new Deadline(created.plus(Duration.ofHours(policy.autoActivateHours())), this::autoActivate));
            }
            return Optional.of(new Deadline(created.plus(Duration.ofHours(policy.confirmTimeoutHours())), this::expireUnconfirmed));
        }

        if (promise.due() == null) {
            if (!sent.contains(NUDGE_KEY)) {
                return Optional.of(new Deadline(created.plus(Duration.ofHours(policy.missingDateNudgeHours())), this::nudgeMissingDate));
            }
            return Optional.empty(); // wait for a signal that sets a date or closes the promise
        }

        LocalDate due = LocalDate.parse(promise.due());
        Set<String> sentReminders = new HashSet<>();
        for (String k : sent) {
            if (k.startsWith(REMINDER_PREFIX)) {
                sentReminders.add(k.substring(REMINDER_PREFIX.length()));
            }
        }
        Optional<Instant> reminder = Timing.nextReminder(due, now(), sentReminders, policy);
        if (reminder.isPresent()) {
            Instant at = reminder.get();
            return Optional.of(new Deadline(at, () -> sendReminder(at)));
        }
        if (status == ACTIVE) {
            return Optional.of(new Deadline(Timing.dueCheckTime(due, policy), this::dueReached));
        }
        return Optional.of(new Deadline(Timing.brokenDeadline(due, policy), this::markBroken));
    }

    private void autoActivate() {
        if (set(ACTIVE)) {
            record("activated", "system", Map.of());
        }
    }

    private void expireUnconfirmed() {
        if (set(DISMISSED)) {
            record("expired_unconfirmed", "system", Map.of());
        }
    }

    private void nudgeMissingDate() {
        sent.add(NUDGE_KEY);
        notify(promise.owner(), "When will you " + promise.what() + "? Add a date so I can remind you.",
                List.of("set date", "dismiss"), "missing-date");
    }

    private void sendReminder(Instant at) {
        sent.add(REMINDER_PREFIX + at);
        notify(promise.owner(), "Reminder: you promised to " + promise.what() + " by " + promise.due() + ".",
                List.of("kept", "slip"), "reminder", at.toString());
    }

    private void dueReached() {
        if (set(AT_RISK)) {
            record("due_unconfirmed", "system", Map.of("due", promise.due()));
            notify(promise.owner(), "Due today: did you " + promise.what() + "?", List.of("kept", "new date"), "due", promise.due());
        }
    }

    private void markBroken() {
        if (set(PromiseStatus.BROKEN)) {
            record("broken", "system", Map.of("due", promise.due()));
            ripple(promise.due(), null, "missed");
            notify(promise.owner(), "Marked as missed: " + promise.what() + " (due " + promise.due() + ").", List.of(), "broken");
            if (promise.recipient() != null) {
                notify(promise.recipient(), promise.owner() + " missed: " + promise.what() + " (due " + promise.due() + ").",
                        List.of(), "broken-recipient");
            }
        }
    }

    // ------------------------------------------------------------ signals

    private void apply(PromiseSignal s) {
        String key = s.kind() + ":" + s.sourceId();
        if (!appliedSet.add(key)) {
            return; // duplicate delivery
        }
        applied.add(key);
        events++;

        PromiseStatus status = promise.status();
        switch (s.kind()) {
            case "confirm" -> {
                String oldDue = promise.due();
                Map<String, String> edits = applyEdits(s);
                if ((status == DETECTED || status == AT_RISK) && set(ACTIVE)) {
                    record("confirmed", s.actor(), edits);
                } else if (!edits.isEmpty()) {
                    record("edited", s.actor(), edits);
                }
                if (later(oldDue, promise.due()) && !fromPlan(s)) {
                    ripple(oldDue, promise.due(), "slipped");
                }
            }
            case "dismiss" -> {
                PromiseStatus target = status == DETECTED ? DISMISSED : CANCELLED;
                if (set(target)) {
                    record(target.dbValue(), s.actor(), Map.of());
                }
            }
            case "update" -> {
                addEvidence(s);
                if (s.due() != null && !s.due().equals(promise.due())) {
                    String old = promise.due();
                    promise = promise.withDue(s.due());
                    record("date_changed", s.actor(), details("old_due", old, "new_due", s.due()));
                    if (later(old, s.due()) && !fromPlan(s)) {
                        ripple(old, s.due(), "slipped");
                    }
                } else {
                    record("restated", s.actor(), Map.of());
                }
            }
            case "slip" -> {
                addEvidence(s);
                String old = promise.due();
                if (s.due() != null) {
                    promise = promise.withDue(s.due());
                }
                if (status == ACTIVE) {
                    set(AT_RISK);
                }
                record("slipped", s.actor(), details("old_due", old, "new_due", promise.due()));
                if (s.due() == null || later(old, s.due())) {
                    ripple(old, s.due() == null ? null : promise.due(), "slipped"); // no new date: "may be delayed"
                }
                String when = promise.due() == null ? "a later date" : promise.due();
                notify(promise.owner(), "Noted: " + promise.what() + " moves to " + when + ". Confirm the new date?",
                        List.of("confirm"), "slip", s.sourceId());
                if (promise.recipient() != null) {
                    notify(promise.recipient(), "Update from " + promise.owner() + ": " + promise.what() + " now expected "
                            + (promise.due() == null ? "later" : promise.due()) + ".", List.of(), "slip-recipient", s.sourceId());
                }
            }
            case "kept" -> {
                addEvidence(s);
                if (status == DETECTED) {
                    set(ACTIVE);
                }
                if (set(KEPT)) {
                    record("kept", s.actor(), Map.of());
                }
            }
            case "cancel" -> {
                if (set(CANCELLED)) {
                    record("cancelled", s.actor(), Map.of());
                }
            }
            case "upstream_risk" -> {
                // From a ripple: something this promise depends on slipped. The owner decides.
                if (status == ACTIVE) {
                    set(AT_RISK);
                }
                record("upstream_slipped", s.actor(), details("upstream", s.what(), "likely_due", s.due()));
                String due = promise.due() == null ? "" : " (due " + promise.due() + ")";
                String likely = s.due() == null ? ", so it may be delayed" : ", so it now looks likely around " + s.due();
                notify(promise.owner(), s.what() + ". Your promise to " + promise.what() + due + " depends on it" + likely
                        + ". Keep your date or set a new one?", List.of("keep date", "new date"), "upstream", s.sourceId());
            }
            case "reassign" -> {
                // From a handoff: someone else covers this promise, or its owner is back.
                String to = s.newOwner();
                if (to != null && !to.equals(promise.owner())) {
                    String from = promise.owner();
                    promise = promise.withOwner(to);
                    record("reassigned", s.actor(), details("from", from, "to", to));
                    String due = promise.due() == null ? "" : " (due " + promise.due() + ")";
                    notify(to, "You now own the promise to " + promise.what() + due + ", handed over from " + from + ".",
                            List.of("kept", "new date"), "reassigned", s.sourceId());
                }
            }
            default -> log.warn("unknown signal kind {}", s.kind());
        }
    }

    private Map<String, String> applyEdits(PromiseSignal s) {
        Map<String, String> edits = new LinkedHashMap<>();
        if (s.due() != null && !s.due().equals(promise.due())) {
            edits.put("due", s.due());
            promise = promise.withDue(s.due());
        }
        if (s.what() != null && !s.what().equals(promise.what())) {
            edits.put("what", s.what());
            promise = promise.withWhat(s.what());
        }
        return edits;
    }

    private void addEvidence(PromiseSignal s) {
        if (s.quote() != null) {
            promise = promise.withEvidence(new Evidence(s.sourceId(), s.quote(), s.channel()));
        }
    }

    // ------------------------------------------------------------ helpers

    private static Map<String, String> details(String k1, String v1, String k2, String v2) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(k1, v1 == null ? "" : v1);
        m.put(k2, v2 == null ? "" : v2);
        return m;
    }

    /**
     * Changes made by an agreed recovery plan don't ripple again: the plan already covered every
     * promise downstream, and a ripple would start a second negotiation about the same slip.
     */
    private static boolean fromPlan(PromiseSignal s) {
        return s.sourceId() != null && s.sourceId().startsWith("plan:");
    }

    private static boolean later(String oldDue, String newDue) {
        return oldDue != null && newDue != null && LocalDate.parse(newDue).isAfter(LocalDate.parse(oldDue));
    }

    /**
     * Starts a ripple for this change and waits only until it has started. The ripple outlives this
     * workflow (a missed promise closes right after), and its ID is unique per recorded change.
     */
    private void ripple(String oldDue, String newDue, String cause) {
        if (Workflow.getVersion(RIPPLE_CHANGE, Workflow.DEFAULT_VERSION, 1) == Workflow.DEFAULT_VERSION) {
            return;
        }
        ChildWorkflowOptions options = ChildWorkflowOptions.newBuilder()
                .setWorkflowId("ripple-" + promise.id() + "-v" + promise.version())
                .setTaskQueue(TASK_QUEUE_MAIN)
                .setParentClosePolicy(ParentClosePolicy.PARENT_CLOSE_POLICY_ABANDON)
                .build();
        RippleWorkflow child = Workflow.newChildWorkflowStub(RippleWorkflow.class, options);
        Async.function(child::run, new RippleRequest(promise.tenant(), promise.id(), oldDue, newDue, cause));
        Workflow.getWorkflowExecution(child).get();
    }

    private boolean set(PromiseStatus target) {
        if (!Lifecycle.canTransition(promise.status(), target)) {
            log.info("ignored transition {} -> {}", promise.status(), target);
            return false;
        }
        promise = promise.withStatus(target);
        return true;
    }

    private void record(String kind, String actor, Map<String, String> detail) {
        promise = promise.withVersion(promise.version() + 1, now().toString());
        io.recordPromise(new RecordRequest(promise, kind, actor, detail));
    }

    private void notify(String recipient, String text, List<String> actions, String... keyParts) {
        List<String> parts = new ArrayList<>(List.of(promise.tenant(), promise.id(), recipient));
        parts.addAll(List.of(keyParts));
        String key = Ids.notificationKey(parts.toArray(String[]::new));
        io.sendNotification(Notification.about(promise.tenant(), recipient, text, key, actions, promise.id()));
    }

    private void announce() {
        String due = promise.due() == null ? "" : " by " + promise.due();
        if (promise.confidence() >= threshold) {
            notify(promise.owner(), "Tracking your promise: " + promise.what() + due + ". Not a promise? Dismiss it.",
                    List.of("dismiss", "edit"), "announce");
        } else {
            notify(promise.owner(), "Did you promise to " + promise.what() + due + "? Confirm and I'll keep track.",
                    List.of("confirm", "dismiss"), "announce");
        }
    }
}
