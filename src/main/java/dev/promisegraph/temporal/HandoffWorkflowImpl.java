package dev.promisegraph.temporal;

import static dev.promisegraph.config.PromiseGraphProperties.TASK_QUEUE_LLM;

import dev.promisegraph.domain.Ids;
import dev.promisegraph.handoff.Absence;
import dev.promisegraph.handoff.HandoffPlanning;
import dev.promisegraph.handoff.HandoffRecord;
import dev.promisegraph.handoff.HandoffRecord.ItemState;
import dev.promisegraph.notify.Notification;
import dev.promisegraph.temporal.Contracts.HandBackRequest;
import dev.promisegraph.temporal.Contracts.HandoffRequest;
import dev.promisegraph.temporal.Contracts.HandoffResponse;
import dev.promisegraph.temporal.Contracts.PlanRequest;
import dev.promisegraph.temporal.Contracts.TransferRequest;
import io.temporal.activity.ActivityOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;

/** Deterministic code only; every side effect is an activity. */
public class HandoffWorkflowImpl implements HandoffWorkflow {

    /** Departures wait for the lead to approve the plan; handoffs recorded before this replay without it. */
    static final String DEPARTURE_APPROVAL = "departure-approval";
    /** Plan and ask for cover as soon as the absence is known, and pick up promises made since at the start. */
    static final String PLAN_ON_APPLY = "plan-on-apply";
    /** A cover who answers after the absence started still takes the promise over. */
    static final String LATE_ACCEPT = "late-accept";
    /**
     * Leave and departures alike: the lead (or an admin) assigns every promise and approves, and the
     * promises then simply become the new owners'; nobody is asked to accept.
     */
    static final String LEAD_ASSIGNS = "lead-assigns";

    /** Handoff moments happen at 09:30 India time (04:00 UTC), like reminders. */
    static final LocalTime HANDOFF_TIME_UTC = LocalTime.of(4, 0);

    private static final Logger log = Workflow.getLogger(HandoffWorkflowImpl.class);

    private final HandoffActivities handoffs = Workflow.newActivityStub(HandoffActivities.class, RetryPolicies.io(Duration.ofMinutes(1)));
    private final PromiseActivities io = Workflow.newActivityStub(PromiseActivities.class, RetryPolicies.io(Duration.ofSeconds(30)));
    private final PlanningActivities planning = Workflow.newActivityStub(PlanningActivities.class,
            ActivityOptions.newBuilder()
                    .setTaskQueue(TASK_QUEUE_LLM)
                    .setStartToCloseTimeout(Duration.ofMinutes(5))
                    .setRetryOptions(RetryPolicies.MODEL_RETRY)
                    .build());

    private final Deque<HandoffResponse> responses = new ArrayDeque<>();
    private final Set<String> handled = new HashSet<>();
    private final Map<String, ItemState> items = new LinkedHashMap<>();
    private HandoffRecord state;
    private Absence absence;
    private String decider;
    private String planner;
    private boolean cancelled;
    private boolean live; // after the absence starts, an accepted promise moves at once
    private boolean lateAccept;
    private boolean departure; // leaving for good: the lead approves first, and nothing comes back
    private boolean approved = true; // only a departure starts unapproved
    private boolean leadMode; // the lead assigns; see LEAD_ASSIGNS

    @Override
    public void respond(HandoffResponse response) {
        responses.addLast(response);
    }

    @Override
    public HandoffRecord state() {
        return state;
    }

    private static Instant at(String isoDate) {
        return LocalDate.parse(isoDate).atTime(HANDOFF_TIME_UTC).toInstant(ZoneOffset.UTC);
    }

    private static Instant now() {
        return Instant.ofEpochMilli(Workflow.currentTimeMillis());
    }

    @Override
    public String run(HandoffRequest request) {
        absence = request.absence();
        Instant startAt = at(absence.startsOn());
        Instant prepAt = startAt.minus(Duration.ofDays(request.prepDays()));
        Instant reminderAt = startAt.minus(Duration.ofDays(1)); // may move later, so not final
        Instant returnAt = absence.endsOn() == null ? null : at(absence.endsOn()).plus(Duration.ofDays(1));

        state = new HandoffRecord(absence.tenant(), absence.handoffId(), absence.person(), absence.startsOn(),
                absence.endsOn(), absence.reason(), "scheduled", null, List.of(), now().toString());
        save("scheduled");

        boolean planEarly = Workflow.getVersion(PLAN_ON_APPLY, Workflow.DEFAULT_VERSION, 1) >= 1;
        if (!planEarly) {
            waitUntil(prepAt);
            if (cancelled) {
                return finishCancelled();
            }
        }

        // 1. Plan who covers what: as soon as the absence is known (it used to wait until a few days before).
        save("planning");
        plan();
        if (items.isEmpty() && planEarly && now().isBefore(prepAt)) {
            // Nothing open yet: look again a few days before, in case new promises were made.
            save("scheduled");
            waitUntil(prepAt);
            if (cancelled) {
                return finishCancelled();
            }
            save("planning");
            plan();
        }
        if (items.isEmpty()) {
            save("nothing_to_hand_over");
            notify(absence.person(), "You have no open promises to hand over while you're away " + window() + ".",
                    List.of(), null, "empty");
            return state.status();
        }

        Instant transferAt = startAt;
        leadMode = Workflow.getVersion(LEAD_ASSIGNS, Workflow.DEFAULT_VERSION, 1) >= 1;
        if (leadMode) {
            // 1-3. The lead assigns every promise and approves; then they're the new owners'.
            departure = returnAt == null;
            String ended = leadAssigns(startAt, returnAt);
            if (ended != null) {
                return ended;
            }
            if (!now().isBefore(startAt)) {
                transferAt = now(); // approved after the start: move at once
            }
        } else {
        // 1b. Someone leaving the company: their lead approves the plan before anyone is asked.
        departure = returnAt == null && Workflow.getVersion(DEPARTURE_APPROVAL, Workflow.DEFAULT_VERSION, 1) >= 1;
        if (departure) {
            approved = false;
            save("awaiting_approval");
            List<String> plan1 = new ArrayList<>();
            for (ItemState i : items.values()) {
                plan1.add(i.what() + " → " + (i.proposedOwner() == null ? "needs someone" : i.proposedOwner()));
            }
            notifyPlan(decider, absence.person() + " is leaving: their last day is " + lastDay() + ". " + items.size()
                    + (items.size() == 1 ? " open promise needs" : " open promises need") + " a new owner: " + String.join("; ", plan1)
                    + ". Approve this plan, or change who takes what on the Handoffs page first.", "approval");
            boolean late = false;
            while (!approved && !cancelled) {
                waitUntil(late ? null : startAt);
                if (!approved && !cancelled && !late) {
                    late = true; // the last day has passed with nothing approved: say so once, then keep waiting
                    notifyPlan(decider, absence.person() + " has left and their " + items.size()
                            + " open promise(s) haven't moved yet. Approve the handover on the Handoffs page.", "late");
                }
            }
            if (cancelled) {
                return finishCancelled();
            }
            if (!now().isBefore(startAt)) {
                transferAt = now().plus(Duration.ofDays(1)); // approved after the last day: give the new owners a day to answer
            }
        }

        // 2. Ask for cover, and tell the person leaving who was asked.
        save("awaiting_cover");
        List<String> lines = new ArrayList<>();
        for (ItemState i : items.values()) {
            lines.add(i.what() + " → " + (i.proposedOwner() == null ? "needs someone" : i.proposedOwner()));
        }
        notify(absence.person(), (departure ? "After you leave, " : "While you're away " + window() + ", ") + items.size() + " promise"
                + (items.size() == 1 ? " needs" : "s need") + (departure ? " a new owner: " : " cover: ") + String.join("; ", lines)
                + ". You can change who takes what on the Handoffs page.", List.of(), null, "plan");
        for (ItemState i : List.copyOf(items.values())) {
            if (i.proposedOwner() != null) {
                askCover(i, "ask");
            } else {
                escalate(i, "plan");
            }
        }

        // 3. Collect answers until the absence starts, with one reminder the day before.
        if (!transferAt.equals(startAt)) {
            reminderAt = transferAt; // approved late: no separate reminder
        }
        if (now().isBefore(reminderAt)) {
            waitUntil(reminderAt);
            if (!cancelled) {
                for (ItemState i : List.copyOf(items.values())) {
                    if ("proposed".equals(i.state())) {
                        askCover(i, "remind");
                    } else if ("needs_owner".equals(i.state())) {
                        escalate(i, "remind");
                    }
                }
            }
        }
        }
        waitUntil(transferAt);
        if (cancelled) {
            return finishCancelled();
        }
        if (planEarly) {
            // Promises made since the plan was drawn up need cover too (in lead mode, the lead is told below).
            for (ItemState i : plan()) {
                if (leadMode) {
                    continue;
                }
                if (i.proposedOwner() != null) {
                    askCover(i, "ask");
                } else {
                    escalate(i, "plan");
                }
            }
        }
        lateAccept = Workflow.getVersion(LATE_ACCEPT, Workflow.DEFAULT_VERSION, 1) >= 1;

        // 4. The absence starts: move every accepted promise to its cover.
        Map<String, String> owners = new LinkedHashMap<>();
        items.values().stream().filter(i -> "accepted".equals(i.state())).forEach(i -> owners.put(i.promiseId(), i.acceptedOwner()));
        List<String> moved = owners.isEmpty() ? List.of()
                : handoffs.transfer(new TransferRequest(absence.tenant(), absence.handoffId(), "out", owners));
        for (ItemState i : List.copyOf(items.values())) {
            if (moved.contains(i.promiseId())) {
                items.put(i.promiseId(), i.withState("transferred"));
            } else if ("accepted".equals(i.state())) {
                items.put(i.promiseId(), i.withState("closed")); // kept or cancelled before the absence
            } else {
                boolean waiting = lateAccept && !leadMode && "proposed".equals(i.state());
                items.put(i.promiseId(), i.withState("uncovered"));
                notify(decider, leadMode
                        ? "\"" + i.what() + "\" (" + absence.person() + "'s) isn't assigned to anyone " + (departure ? "now that they've left" : "while they're away " + window())
                                + (i.proposedOwner() == null ? "" : "; suggested: " + i.proposedOwner()) + ". Assign it on the Handoffs page."
                        : waiting
                        ? i.proposedOwner() + " hasn't answered yet about covering \"" + i.what() + "\" for " + absence.person() + " ("
                                + window() + "). It moves to them as soon as they accept, or choose someone else on the Handoffs page."
                        : "Nobody is covering \"" + i.what() + "\" while " + absence.person() + " is away "
                                + window() + ". Choose someone on the Handoffs page.", List.of(), i.promiseId(), "uncovered", i.promiseId());
            }
        }
        live = true;
        save(departure ? "handed_over" : "active");

        if (returnAt == null) {
            return state.status(); // a permanent exit: the new owners keep the promises
        }

        // 5. The day after the absence ends: hand back and summarise. A cancel signal means an early return.
        waitUntil(returnAt);
        Map<String, String> covers = new LinkedHashMap<>();
        items.values().stream().filter(i -> "transferred".equals(i.state())).forEach(i -> covers.put(i.promiseId(), i.acceptedOwner()));
        List<String> back = handoffs.handBack(new HandBackRequest(absence.tenant(), absence.handoffId(), absence.person(),
                startAt.toString(), covers));
        for (ItemState i : List.copyOf(items.values())) {
            if ("transferred".equals(i.state())) {
                items.put(i.promiseId(), i.withState(back.contains(i.promiseId()) ? "returned" : "closed"));
            }
        }
        save("returned");
        return state.status();
    }

    /**
     * Waits until {@code until}, handling answers as they arrive. Stops early on cancel, and, while a
     * departure awaits approval, once it is approved. {@code null} means no deadline.
     */
    private void waitUntil(Instant until) {
        while (!cancelled) {
            boolean waitingForApproval = !approved;
            processResponses();
            if (cancelled || (waitingForApproval && approved)) {
                return;
            }
            if (until == null) {
                Workflow.await(() -> !responses.isEmpty() || cancelled);
                continue;
            }
            Duration left = Duration.between(now(), until);
            if (left.isNegative() || left.isZero()) {
                return;
            }
            Workflow.await(left, () -> !responses.isEmpty() || cancelled);
        }
    }

    private void processResponses() {
        boolean changed = false;
        while (!responses.isEmpty()) {
            HandoffResponse r = responses.pollFirst();
            if (!handled.add(r.kind() + ":" + r.sourceId())) {
                continue; // duplicate delivery
            }
            if ("cancel".equals(r.kind())) {
                cancelled = true;
                continue;
            }
            if ("approve plan".equals(r.kind())) {
                if (!approved && mayDecide(r.by())) {
                    approved = true;
                    if (!leadMode) {
                        notify(absence.person(), r.by() + " approved the handover of your open promises.", List.of(), null, "approved");
                    }
                    changed = true;
                }
                continue;
            }
            ItemState i = items.get(r.promiseId());
            if (i == null) {
                log.info("ignored {} for unknown promise {}", r.kind(), r.promiseId());
                continue;
            }
            switch (r.kind()) {
                case "accept" -> {
                    boolean late = lateAccept && "uncovered".equals(i.state()) && i.acceptedOwner() == null;
                    if (("proposed".equals(i.state()) || late) && r.by().equals(i.proposedOwner())) {
                        items.put(i.promiseId(), i.accepted(r.by()));
                        notify(absence.person(), r.by() + " will cover \"" + i.what() + "\" while you're away.",
                                List.of(), i.promiseId(), "accepted", i.promiseId());
                        if (live) {
                            moveNow(items.get(i.promiseId()));
                        }
                        changed = true;
                    }
                }
                case "decline" -> {
                    if ("proposed".equals(i.state()) && r.by().equals(i.proposedOwner())) {
                        ItemState declined = i.declined(r.by() + " declined");
                        items.put(i.promiseId(), live ? declined.withState("uncovered") : declined);
                        escalate(items.get(i.promiseId()), "declined-" + r.by());
                        changed = true;
                    }
                }
                case "assign" -> {
                    boolean open = "proposed".equals(i.state()) || "needs_owner".equals(i.state()) || "uncovered".equals(i.state());
                    if (open && r.owner() != null && !r.owner().isBlank() && !r.owner().equals(absence.person())
                            && (!leadMode || mayDecide(r.by()))) {
                        ItemState next = i.withProposal(r.owner(), "chosen by " + r.by());
                        items.put(i.promiseId(), next);
                        if (leadMode) {
                            if (approved) {
                                assignTo(next, "assigned-" + r.sourceId()); // after approval, the lead's choice is final
                            }
                        } else if (approved) {
                            askCover(next, "assign-" + r.sourceId()); // before approval, only the plan changes
                        }
                        changed = true;
                    }
                }
                default -> log.warn("unknown handoff response {}", r.kind());
            }
        }
        if (changed) {
            save(state.status().equals("awaiting_cover") && allAccepted() ? "covered" : state.status());
        }
    }

    /**
     * Lead mode, steps 1-3: the lead sees every open promise (with suggestions) on the Handoffs page,
     * changes who takes what, and approves. Returns a final status if the handoff ended instead.
     */
    private String leadAssigns(Instant startAt, Instant returnAt) {
        approved = false;
        save("awaiting_approval");
        List<String> plan1 = new ArrayList<>();
        for (ItemState i : items.values()) {
            plan1.add(i.what() + (i.due() == null ? "" : " (due " + i.due() + ")") + " → "
                    + (i.proposedOwner() == null ? "nobody suggested" : "suggested: " + i.proposedOwner()));
        }
        notifyPlan(decider, absence.person() + (departure ? " is leaving: their last day is " + lastDay() : " is on leave " + window()) + ". "
                + "Assign their " + items.size() + (items.size() == 1 ? " open promise" : " open promises") + " to teammates: "
                + String.join("; ", plan1) + ". Change who takes what on the Handoffs page, then approve. Nobody gets anything before that.",
                "approval");
        if (!absence.person().equals(decider)) {
            notify(absence.person(), "Your " + items.size() + (items.size() == 1 ? " open promise goes" : " open promises go") + " to "
                    + decider + " to assign to teammates " + (departure ? "before you leave" : "while you're away " + window())
                    + ". You'll be told who takes what.", List.of(), null, "plan");
        }
        boolean late = false;
        while (!approved && !cancelled) {
            waitUntil(late ? returnAt : startAt);
            if (approved || cancelled) {
                break;
            }
            if (!late) {
                late = true; // the absence started with nothing approved: say so once
                notifyPlan(decider, absence.person() + (departure ? " has left" : "'s leave has started") + " and their " + items.size()
                        + " open promise(s) aren't assigned yet. Assign them on the Handoffs page.", "late");
            } else if (returnAt != null && !now().isBefore(returnAt)) {
                for (ItemState i : List.copyOf(items.values())) {
                    items.put(i.promiseId(), i.withState("closed"));
                }
                save("not_assigned"); // the leave ended before anything was assigned: nothing to move back
                return state.status();
            }
        }
        if (cancelled) {
            return finishCancelled();
        }
        List<String> lines = new ArrayList<>();
        for (ItemState i : List.copyOf(items.values())) {
            if (i.proposedOwner() != null) {
                assignTo(i, "assigned");
                lines.add(i.what() + " → " + i.proposedOwner());
            } else {
                lines.add(i.what() + " → stays with you for now");
            }
        }
        notify(absence.person(), decider + " assigned your open promises: " + String.join("; ", lines) + ".", List.of(), null, "assigned");
        save(allAccepted() ? "covered" : "awaiting_cover");
        return null;
    }

    /** Lead mode: the promise is the new owner's (it moves at the start, or now if that has passed). */
    private void assignTo(ItemState i, String why) {
        String owner = i.proposedOwner();
        items.put(i.promiseId(), i.accepted(owner));
        String due = i.due() == null ? "" : " (due " + i.due() + ")";
        String when = live ? "It's yours from now" : "It becomes yours on " + absence.startsOn();
        notify(owner, decider + " assigned you \"" + i.what() + "\"" + due + " from " + absence.person()
                + (departure ? ", who is leaving (last day " + lastDay() + "). " + when + ", for good. "
                        : " while they're away " + window() + ". " + when + ", and it goes back to them afterwards. ")
                + i.brief(), List.of(), i.promiseId(), why, i.promiseId(), owner);
        if (live) {
            moveNow(items.get(i.promiseId()));
        }
    }

    /** Lead mode: the lead or an admin decides, never the person away (unless nobody else can). */
    private boolean mayDecide(String by) {
        if (by == null) {
            return false;
        }
        return by.equals(decider) || (leadMode && !by.equals(absence.person()));
    }

    /** Plans cover for the person's open promises not planned yet; returns the new items. */
    private List<ItemState> plan() {
        HandoffPlanning.Plan plan = planning.planHandoff(new PlanRequest(absence.tenant(), absence.handoffId(),
                absence.person(), absence.startsOn(), absence.endsOn()));
        if (planner == null) {
            planner = plan.planner();
            decider = plan.decider();
        }
        List<ItemState> added = new ArrayList<>();
        for (HandoffPlanning.Item i : plan.items()) {
            if (!items.containsKey(i.promiseId())) {
                ItemState item = new ItemState(i.promiseId(), i.what(), i.due(), i.brief(), i.proposedOwner(), null,
                        i.proposedOwner() == null ? "needs_owner" : "proposed", i.reason());
                items.put(i.promiseId(), item);
                added.add(item);
            }
        }
        return added;
    }

    private boolean allAccepted() {
        return items.values().stream().allMatch(i -> "accepted".equals(i.state()));
    }

    private void moveNow(ItemState i) {
        List<String> moved = handoffs.transfer(new TransferRequest(absence.tenant(), absence.handoffId(), "out",
                Map.of(i.promiseId(), i.acceptedOwner())));
        items.put(i.promiseId(), i.withState(moved.contains(i.promiseId()) ? "transferred" : "closed"));
    }

    private String window() {
        return absence.endsOn() == null ? "from " + absence.startsOn() : "from " + absence.startsOn() + " to " + absence.endsOn();
    }

    /** For a departure, the person's last day (the day before the absence starts). */
    private String lastDay() {
        return LocalDate.parse(absence.startsOn()).minusDays(1).toString();
    }

    private void askCover(ItemState i, String why) {
        String due = i.due() == null ? "" : " (due " + i.due() + ")";
        String ask = departure
                ? "Can you take over \"" + i.what() + "\"" + due + " from " + absence.person() + ", who is leaving (last day "
                        + lastDay() + ")? It becomes your promise for good. "
                : "Can you cover \"" + i.what() + "\"" + due + " for " + absence.person() + " while they're away " + window()
                        + "? It comes back to them afterwards. ";
        notify(i.proposedOwner(), ask + i.brief(), List.of("accept", "decline"), i.promiseId(), why, i.promiseId(), i.proposedOwner());
    }

    /** The plan-approval request: its subject routes the inbox button to this workflow. */
    private void notifyPlan(String recipient, String text, String why) {
        List<String> parts = List.of(absence.tenant(), "handoff", absence.handoffId(), recipient, "plan", why);
        io.sendNotification(new Notification(absence.tenant(), recipient, text, Ids.notificationKey(parts.toArray(String[]::new)),
                List.of("approve plan"), null, "handoff:" + absence.handoffId() + ":plan"));
    }

    private void escalate(ItemState i, String why) {
        notify(decider, "Nobody is lined up to cover \"" + i.what() + "\" for " + absence.person() + " (away " + window()
                + "). Choose someone on the Handoffs page.", List.of(), i.promiseId(), "escalate-" + why, i.promiseId());
    }

    private String finishCancelled() {
        for (ItemState i : List.copyOf(items.values())) {
            String who = i.acceptedOwner() != null ? i.acceptedOwner() : i.proposedOwner();
            if (who != null && ("proposed".equals(i.state()) || "accepted".equals(i.state()))) {
                notify(who, "No cover needed any more for \"" + i.what() + "\": " + absence.person() + "'s absence was cancelled.",
                        List.of(), i.promiseId(), "cancelled", i.promiseId());
            }
            items.put(i.promiseId(), i.withState("closed"));
        }
        save("cancelled");
        return state.status();
    }

    private void save(String status) {
        state = state.with(status, new ArrayList<>(items.values()), planner, now().toString());
        handoffs.saveHandoff(state);
    }

    /** Handoff notifications carry a subject so inbox buttons reach this workflow. */
    private void notify(String recipient, String text, List<String> actions, String promiseId, String... keyParts) {
        if (recipient == null) {
            return;
        }
        List<String> parts = new ArrayList<>(List.of(absence.tenant(), "handoff", absence.handoffId(), recipient));
        parts.addAll(List.of(keyParts));
        String subject = promiseId == null ? null : "handoff:" + absence.handoffId() + ":" + promiseId;
        io.sendNotification(new Notification(absence.tenant(), recipient, text, Ids.notificationKey(parts.toArray(String[]::new)),
                actions, promiseId, subject));
    }
}
