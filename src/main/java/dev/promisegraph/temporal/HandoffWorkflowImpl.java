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
        Instant reminderAt = startAt.minus(Duration.ofDays(1));
        Instant returnAt = absence.endsOn() == null ? null : at(absence.endsOn()).plus(Duration.ofDays(1));

        state = new HandoffRecord(absence.tenant(), absence.handoffId(), absence.person(), absence.startsOn(),
                absence.endsOn(), absence.reason(), "scheduled", null, List.of(), now().toString());
        save("scheduled");

        waitUntil(prepAt);
        if (cancelled) {
            return finishCancelled();
        }

        // 1. Plan who covers what.
        save("planning");
        HandoffPlanning.Plan plan = planning.planHandoff(new PlanRequest(absence.tenant(), absence.handoffId(),
                absence.person(), absence.startsOn(), absence.endsOn()));
        planner = plan.planner();
        decider = plan.decider();
        for (HandoffPlanning.Item i : plan.items()) {
            items.put(i.promiseId(), new ItemState(i.promiseId(), i.what(), i.due(), i.brief(), i.proposedOwner(), null,
                    i.proposedOwner() == null ? "needs_owner" : "proposed", i.reason()));
        }
        if (items.isEmpty()) {
            save("nothing_to_hand_over");
            notify(absence.person(), "You have no open promises to hand over while you're away " + window() + ".",
                    List.of(), null, "empty");
            return state.status();
        }

        // 2. Ask for cover, and tell the person leaving who was asked.
        save("awaiting_cover");
        List<String> lines = new ArrayList<>();
        for (ItemState i : items.values()) {
            lines.add(i.what() + " → " + (i.proposedOwner() == null ? "needs someone" : i.proposedOwner()));
        }
        notify(absence.person(), "While you're away " + window() + ", " + items.size() + " promise"
                + (items.size() == 1 ? " needs" : "s need") + " cover: " + String.join("; ", lines)
                + ". You can change who covers what on the Handoffs page.", List.of(), null, "plan");
        for (ItemState i : List.copyOf(items.values())) {
            if (i.proposedOwner() != null) {
                askCover(i, "ask");
            } else {
                escalate(i, "plan");
            }
        }

        // 3. Collect answers until the absence starts, with one reminder the day before.
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
        waitUntil(startAt);
        if (cancelled) {
            return finishCancelled();
        }

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
                items.put(i.promiseId(), i.withState("uncovered"));
                notify(decider, "Nobody is covering \"" + i.what() + "\" while " + absence.person() + " is away "
                        + window() + ". Choose someone on the Handoffs page.", List.of(), i.promiseId(), "uncovered", i.promiseId());
            }
        }
        live = true;
        save("active");

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

    /** Waits until {@code until}, handling answers as they arrive. Stops early on cancel. */
    private void waitUntil(Instant until) {
        while (!cancelled) {
            processResponses();
            if (cancelled) {
                return;
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
            ItemState i = items.get(r.promiseId());
            if (i == null) {
                log.info("ignored {} for unknown promise {}", r.kind(), r.promiseId());
                continue;
            }
            switch (r.kind()) {
                case "accept" -> {
                    if ("proposed".equals(i.state()) && r.by().equals(i.proposedOwner())) {
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
                    if (open && r.owner() != null && !r.owner().isBlank() && !r.owner().equals(absence.person())) {
                        ItemState next = i.withProposal(r.owner(), "chosen by " + r.by());
                        items.put(i.promiseId(), next);
                        askCover(next, "assign-" + r.sourceId());
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

    private void askCover(ItemState i, String why) {
        String due = i.due() == null ? "" : " (due " + i.due() + ")";
        notify(i.proposedOwner(), "Can you cover \"" + i.what() + "\"" + due + " for " + absence.person() + " while they're away "
                + window() + "? " + i.brief(), List.of("accept", "decline"), i.promiseId(), why, i.promiseId(), i.proposedOwner());
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
