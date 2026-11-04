package dev.promisegraph.temporal;

import static dev.promisegraph.config.PromiseGraphProperties.TASK_QUEUE_LLM;

import dev.promisegraph.domain.Ids;
import dev.promisegraph.notify.Notification;
import dev.promisegraph.recovery.Move;
import dev.promisegraph.recovery.NegotiationRecord;
import dev.promisegraph.recovery.RecoveryPlan;
import dev.promisegraph.recovery.RecoveryPlanning;
import dev.promisegraph.temporal.Contracts.RippleRequest;
import dev.promisegraph.temporal.NegotiationContracts.ApplyStep;
import dev.promisegraph.temporal.NegotiationContracts.NegotiationRequest;
import dev.promisegraph.temporal.NegotiationContracts.PrecheckRequest;
import dev.promisegraph.temporal.NegotiationContracts.ProposeRequest;
import dev.promisegraph.temporal.NegotiationContracts.ReleaseRequest;
import dev.promisegraph.temporal.NegotiationContracts.ReserveRequest;
import dev.promisegraph.temporal.NegotiationContracts.ReserveResult;
import dev.promisegraph.temporal.NegotiationContracts.Vote;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.workflow.Saga;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;

/** Deterministic code only; every side effect is an activity. */
public class NegotiationWorkflowImpl implements NegotiationWorkflow {

    private static final Logger log = Workflow.getLogger(NegotiationWorkflowImpl.class);
    static final String REJECT = "reject";

    private final NegotiationActivities negotiations = Workflow.newActivityStub(NegotiationActivities.class, RetryPolicies.io(Duration.ofMinutes(1)));
    private final PromiseActivities io = Workflow.newActivityStub(PromiseActivities.class, RetryPolicies.io(Duration.ofSeconds(30)));
    private final RecoveryActivities recovery = Workflow.newActivityStub(RecoveryActivities.class,
            ActivityOptions.newBuilder()
                    .setTaskQueue(TASK_QUEUE_LLM)
                    .setStartToCloseTimeout(Duration.ofMinutes(5))
                    .setRetryOptions(RetryPolicies.MODEL_RETRY)
                    .build());
    /** A closed promise fails the step at once, so the saga undoes the steps before it. */
    private final NegotiationActivities apply = Workflow.newActivityStub(NegotiationActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofMinutes(1))
                    .setRetryOptions(RetryOptions.newBuilder(RetryPolicies.IO_RETRY)
                            .setDoNotRetry(NegotiationActivitiesImpl.PROMISE_CLOSED, "UnknownMove").build())
                    .build());

    private enum Outcome { APPROVED, REJECTED, SPLIT, EXPIRED, STOPPED }

    private final Deque<Vote> inbox = new ArrayDeque<>();
    private final Set<String> handled = new HashSet<>();
    private final Map<String, String> votes = new LinkedHashMap<>();
    private final Map<String, String> reasons = new LinkedHashMap<>();
    private final List<String> history = new ArrayList<>();

    private NegotiationRequest request;
    private String tenant;
    private String id;
    private String status = "planning";
    private int round;
    private String context = "";
    private String planner = "";
    private List<RecoveryPlan> plans = List.of();
    private List<RecoveryPlan> dropped = List.of();
    private List<String> approvers = List.of();
    private List<String> owners = List.of();
    private String chosen;
    private String deadline;
    private String createdAt;
    private String stopKind;
    private String stopReason;

    @Override
    public void vote(Vote vote) {
        inbox.addLast(vote);
    }

    @Override
    public NegotiationRecord state() {
        return snapshot();
    }

    private static Instant now() {
        return Instant.ofEpochMilli(Workflow.currentTimeMillis());
    }

    private static String today() {
        return LocalDate.ofInstant(now(), ZoneOffset.UTC).toString();
    }

    @Override
    public String run(NegotiationRequest req) {
        request = req;
        RippleRequest ripple = req.ripple();
        tenant = ripple.tenant();
        id = req.negotiationId();
        createdAt = now().toString();
        note("started: " + req.trigger().reason());
        save();
        negotiations.supersede(tenant, ripple.promiseId(), id);

        List<String> excludedSignatures = new ArrayList<>();
        Set<String> excludedHelpers = new LinkedHashSet<>();
        List<String> notes = new ArrayList<>();
        List<RecoveryPlan> keep = List.of();

        for (round = 1; round <= req.trigger().maxRounds(); round++) {
            processVotes(); // a stop may arrive while planning
            if (stopKind != null) {
                return stopped();
            }
            status = "planning";
            votes.clear();
            reasons.clear();
            RecoveryPlanning.Feedback feedback = new RecoveryPlanning.Feedback(round, excludedSignatures, new ArrayList<>(excludedHelpers), keep, notes);
            RecoveryPlanning.Options options = recovery.proposePlans(new ProposeRequest(id, ripple, req.result(), today(), feedback));
            context = options.context();
            planner = options.planner();
            approvers = options.approvers();
            owners = options.owners();
            List<RecoveryPlan> droppedNow = new ArrayList<>(options.dropped());
            plans = staffed(options.plans(), droppedNow, ripple.newDue());
            dropped = droppedNow;
            note("round " + round + ": " + plans.size() + " plan" + (plans.size() == 1 ? "" : "s") + " offered by " + planner
                    + (dropped.isEmpty() ? "" : ", " + dropped.size() + " dropped"));
            if (plans.isEmpty()) {
                break;
            }

            status = "awaiting_approval";
            Instant until = now().plus(Duration.ofHours(req.trigger().approvalHours()));
            deadline = until.toString();
            save();
            askApprovers();
            if (round == 1) {
                tellOwners();
            }

            Outcome outcome = awaitVotes(until, now().plus(Duration.ofHours(req.trigger().reminderHours())));
            switch (outcome) {
                case APPROVED -> {
                    RecoveryPlan plan = plans.stream().filter(p -> p.id().equals(chosen)).findFirst().orElseThrow();
                    negotiations.release(new ReleaseRequest(tenant, id, NegotiationContracts.reservationKeys(id, plan)));
                    return applyPlan(plan);
                }
                case REJECTED -> {
                    plans.forEach(p -> excludedSignatures.add(p.signature()));
                    plans.forEach(p -> p.moves().stream().filter(Move::addsWork).forEach(m -> excludedHelpers.add(m.person())));
                    reasons.forEach((who, why) -> notes.add(who + ": " + why));
                    keep = List.of();
                    note("round " + round + ": every approver rejected the plans");
                    negotiations.release(new ReleaseRequest(tenant, id, List.of()));
                }
                case SPLIT -> {
                    Set<String> voted = new LinkedHashSet<>(votes.values());
                    keep = plans.stream().filter(p -> voted.contains(p.id())).toList();
                    reasons.forEach((who, why) -> notes.add(who + ": " + why));
                    note("round " + round + ": approvers chose different plans " + votes + "; offering " + voted.stream().filter(v -> !v.equals(REJECT)).toList() + " again");
                    List<String> held = new ArrayList<>();
                    keep.forEach(p -> held.addAll(NegotiationContracts.reservationKeys(id, p)));
                    negotiations.release(new ReleaseRequest(tenant, id, held));
                }
                case EXPIRED -> {
                    negotiations.release(new ReleaseRequest(tenant, id, List.of()));
                    return finish("expired", "No plan was approved by " + deadline + ". Nothing was changed; each owner can still set their own date.");
                }
                case STOPPED -> {
                    return stopped();
                }
                default -> throw new IllegalStateException("unhandled outcome " + outcome);
            }
        }
        negotiations.release(new ReleaseRequest(tenant, id, List.of()));
        int completed = round - 1;
        round = Math.max(1, Math.min(round, req.trigger().maxRounds()));
        return finish("unresolved", plans.isEmpty() && completed == 0
                ? "No recovery plan passed the checks. The owners need to agree new dates directly."
                : "No plan was agreed after " + completed + " round" + (completed == 1 ? "" : "s")
                        + ". The owners and leads need to agree new dates directly.");
    }

    /** Asks the teams to hold the people each plan adds; plans a team can't staff are dropped. Relabels the rest A, B, C. */
    private List<RecoveryPlan> staffed(List<RecoveryPlan> offered, List<RecoveryPlan> droppedNow, String until) {
        if (offered.stream().noneMatch(p -> p.moves().stream().anyMatch(Move::addsWork))) {
            return offered;
        }
        ReserveResult rr = negotiations.reserve(new ReserveRequest(tenant, id, offered, until));
        List<RecoveryPlan> kept = new ArrayList<>();
        for (RecoveryPlan p : offered) {
            if (rr.refused().containsKey(p.id())) {
                droppedNow.add(p.verified(p.score(), List.of(rr.refused().get(p.id()))));
            } else {
                kept.add(p.withId(String.valueOf("ABC".charAt(kept.size()))));
            }
        }
        return kept;
    }

    // ------------------------------------------------------------ approval

    private Outcome awaitVotes(Instant until, Instant remindAt) {
        boolean reminded = !remindAt.isBefore(until);
        while (true) {
            processVotes();
            if (stopKind != null) {
                return Outcome.STOPPED;
            }
            if (votes.keySet().containsAll(approvers)) {
                return decide();
            }
            Instant now = now();
            if (!now.isBefore(until)) {
                return Outcome.EXPIRED;
            }
            if (!reminded && !now.isBefore(remindAt)) {
                reminded = true;
                for (String a : approvers) {
                    if (!votes.containsKey(a)) {
                        notifyApprover(a, "Reminder: a recovery plan is waiting for your choice. " + context
                                + " The deadline is " + deadline + ".", "remind");
                    }
                }
            }
            Instant next = reminded ? until : remindAt;
            Workflow.await(Duration.between(now, next), () -> !inbox.isEmpty());
        }
    }

    private Outcome decide() {
        Set<String> choices = new LinkedHashSet<>(votes.values());
        if (choices.size() == 1) {
            String only = choices.iterator().next();
            if (only.equals(REJECT)) {
                return Outcome.REJECTED;
            }
            chosen = only;
            return Outcome.APPROVED;
        }
        return choices.stream().allMatch(REJECT::equals) ? Outcome.REJECTED : Outcome.SPLIT;
    }

    private void processVotes() {
        boolean changed = false;
        while (!inbox.isEmpty()) {
            Vote v = inbox.pollFirst();
            if (!handled.add(v.kind() + ":" + v.sourceId())) {
                continue; // duplicate delivery
            }
            switch (v.kind()) {
                case "cancel", "superseded" -> {
                    stopKind = v.kind().equals("cancel") ? "cancelled" : "superseded";
                    stopReason = v.reason();
                    return;
                }
                case "approve", REJECT -> {
                    if (!"awaiting_approval".equals(status) || v.round() != round || !approvers.contains(v.by())) {
                        log.info("ignored {} from {} for round {} (now round {}, status {})", v.kind(), v.by(), v.round(), round, status);
                        continue;
                    }
                    if (v.kind().equals("approve") && plans.stream().noneMatch(p -> p.id().equals(v.planId()))) {
                        continue;
                    }
                    String choice = v.kind().equals("approve") ? v.planId() : REJECT;
                    votes.put(v.by(), choice);
                    if (v.reason() != null && !v.reason().isBlank()) {
                        reasons.put(v.by(), v.reason().strip());
                    }
                    note(v.by() + (choice.equals(REJECT) ? " rejected the plans" : " chose plan " + choice)
                            + (v.reason() == null || v.reason().isBlank() ? "" : ": " + v.reason().strip()));
                    changed = true;
                }
                default -> log.warn("unknown vote kind {}", v.kind());
            }
        }
        if (changed) {
            save();
        }
    }

    // ------------------------------------------------------------ applying

    /** The saga: apply each move; if one fails, undo the ones before it in reverse order. */
    private String applyPlan(RecoveryPlan plan) {
        status = "applying";
        note("plan " + plan.id() + " approved by " + String.join(", ", approvers));
        save();
        List<String> problems = negotiations.precheck(new PrecheckRequest(id, request.ripple(), request.result(), today(), plan));
        if (!problems.isEmpty()) {
            negotiations.release(new ReleaseRequest(tenant, id, List.of()));
            return finish("failed", "Plan " + plan.id() + " no longer fits, so nothing was changed: " + String.join("; ", problems) + ".");
        }
        Saga saga = new Saga(new Saga.Options.Builder().setContinueWithError(true).build());
        String approvedBy = String.join(", ", approvers);
        int applied = 0;
        try {
            for (int i = 0; i < plan.moves().size(); i++) {
                ApplyStep step = new ApplyStep(tenant, id, round, plan.id(), i, plan.moves().get(i), approvedBy);
                apply.applyMove(step);
                saga.addCompensation(apply::undoMove, step);
                applied++;
            }
        } catch (ActivityFailure e) {
            String why = e.getCause() instanceof ApplicationFailure af ? af.getOriginalMessage() : e.getMessage();
            try {
                saga.compensate();
            } catch (Saga.CompensationException ce) {
                log.warn("some undo steps failed: {}", ce.getMessage());
            }
            negotiations.release(new ReleaseRequest(tenant, id, List.of()));
            return finish("failed", "Plan " + plan.id() + " could not be applied: " + why + ". The " + applied
                    + " change" + (applied == 1 ? " already made was" : "s already made were") + " undone.");
        }
        List<String> lines = plan.moves().stream().map(Move::describe).toList();
        return finish("applied", "Plan " + plan.id() + " (" + plan.title() + ") is in place: " + String.join("; ", lines) + ".");
    }

    // ------------------------------------------------------------ endings, records and messages

    private String stopped() {
        negotiations.release(new ReleaseRequest(tenant, id, List.of()));
        String why = stopReason == null || stopReason.isBlank() ? "" : " (" + stopReason + ")";
        return finish(stopKind, stopKind.equals("superseded")
                ? "This negotiation was replaced by a newer one" + why + "."
                : "This negotiation was cancelled" + why + ". Nothing was changed.");
    }

    private String finish(String finalStatus, String outcome) {
        status = finalStatus;
        note(outcome);
        save();
        Set<String> told = new LinkedHashSet<>(approvers);
        if (sourceOwner() != null) {
            told.add(sourceOwner());
        }
        if (!finalStatus.equals("superseded")) {
            for (String person : told) {
                send(person, "Recovery plan for \"" + sourceWhat() + "\": " + outcome, List.of(), null, "outcome-" + finalStatus);
            }
        }
        return status;
    }

    private String sourceOwner() {
        return request.trigger().sourceOwner();
    }

    private String sourceWhat() {
        String what = request.trigger().sourceWhat();
        return what == null ? request.ripple().promiseId() : what;
    }

    private void askApprovers() {
        StringBuilder sb = new StringBuilder(context).append(" Choose a recovery plan (round ").append(round).append("): ");
        List<String> lines = new ArrayList<>();
        plans.forEach(p -> lines.add(p.headline()));
        sb.append(String.join(" | ", lines)).append(". The Plans page shows every move. Deadline: ").append(deadline).append('.');
        List<String> actions = new ArrayList<>();
        plans.forEach(p -> actions.add("approve " + p.id()));
        actions.add("reject all");
        for (String a : approvers) {
            send(a, sb.toString(), actions, "negotiation:" + id + ":" + round, "ask-" + round);
        }
    }

    private void tellOwners() {
        for (String o : owners) {
            if (!approvers.contains(o)) {
                send(o, context + " Your lead is choosing a recovery plan; you'll hear what's decided.", List.of(), null, "fyi");
            }
        }
    }

    private void notifyApprover(String approver, String text, String why) {
        List<String> actions = new ArrayList<>();
        plans.forEach(p -> actions.add("approve " + p.id()));
        actions.add("reject all");
        send(approver, text, actions, "negotiation:" + id + ":" + round, why + "-" + round);
    }

    private void send(String recipient, String text, List<String> actions, String subject, String why) {
        String key = Ids.notificationKey(tenant, "negotiation", id, recipient, why);
        io.sendNotification(new Notification(tenant, recipient, text, key, actions, request.ripple().promiseId(), subject));
    }

    private void note(String line) {
        history.add(now() + " " + line);
    }

    private NegotiationRecord snapshot() {
        RippleRequest r = request.ripple();
        return new NegotiationRecord(tenant, id, r.promiseId(), sourceWhat(), sourceOwner(), r.oldDue(), r.newDue(), status, round,
                context, planner, plans, dropped, approvers, votes, chosen, deadline, history, createdAt, now().toString());
    }

    private void save() {
        negotiations.saveNegotiation(snapshot());
    }
}
