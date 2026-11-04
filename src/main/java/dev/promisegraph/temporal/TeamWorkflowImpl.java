package dev.promisegraph.temporal;

import dev.promisegraph.temporal.NegotiationContracts.ReleaseRequest;
import dev.promisegraph.temporal.NegotiationContracts.Reservation;
import dev.promisegraph.temporal.NegotiationContracts.TeamAnswer;
import dev.promisegraph.temporal.NegotiationContracts.TeamInput;
import dev.promisegraph.temporal.NegotiationContracts.TeamSnapshot;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Deterministic code only; the workload comes from an activity. */
public class TeamWorkflowImpl implements TeamWorkflow {

    static final Duration REFRESH_EVERY = Duration.ofHours(24);
    /** Keeps the history short: start afresh, carrying the reservations, after this many answers or refreshes. */
    static final int MAX_UPDATES = 500;
    static final int MAX_REFRESHES = 30;

    private final NegotiationActivities activities = Workflow.newActivityStub(NegotiationActivities.class, RetryPolicies.io(Duration.ofMinutes(1)));

    private final Map<String, Reservation> reservations = new LinkedHashMap<>();
    private Map<String, Integer> workload;
    private String tenant;
    private String team;
    private int capacity;
    private int updates;
    private String refreshedAt;

    private static LocalDate today() {
        return LocalDate.ofInstant(Instant.ofEpochMilli(Workflow.currentTimeMillis()), ZoneOffset.UTC);
    }

    @Override
    public void run(TeamInput input) {
        tenant = input.tenant();
        team = input.team();
        capacity = input.capacity();
        input.reservations().forEach(r -> reservations.put(r.key(), r));
        int refreshes = 0;
        while (true) {
            refresh();
            refreshes++;
            boolean busy = Workflow.await(REFRESH_EVERY, () -> updates >= MAX_UPDATES);
            if (busy || refreshes >= MAX_REFRESHES) {
                Workflow.await(Workflow::isEveryHandlerFinished);
                Workflow.continueAsNew(new TeamInput(tenant, team, capacity, new ArrayList<>(reservations.values())));
            }
        }
    }

    private void refresh() {
        workload = new LinkedHashMap<>(activities.teamWorkload(tenant, team));
        LocalDate today = today();
        reservations.values().removeIf(r -> r.until() != null && LocalDate.parse(r.until()).isBefore(today));
        refreshedAt = Instant.ofEpochMilli(Workflow.currentTimeMillis()).toString();
    }

    private int held(String person) {
        return (int) reservations.values().stream().filter(r -> r.person().equals(person)).count();
    }

    @Override
    public void validateReserve(Reservation r) {
        if (r == null || r.key() == null || r.key().isBlank() || r.person() == null || r.person().isBlank()) {
            throw new IllegalArgumentException("a reservation needs a key and a person");
        }
    }

    @Override
    public TeamAnswer reserve(Reservation r) {
        Workflow.await(() -> workload != null);
        updates++;
        int base = workload.getOrDefault(r.person(), 0);
        if (reservations.containsKey(r.key())) {
            return new TeamAnswer(true, r.person(), base + held(r.person()), capacity, "already held");
        }
        if (!workload.containsKey(r.person())) {
            return new TeamAnswer(false, r.person(), 0, capacity, r.person() + " is not in " + team);
        }
        int load = base + held(r.person());
        if (load + 1 > capacity) {
            return new TeamAnswer(false, r.person(), load, capacity,
                    r.person() + " already has " + load + " open promises or holds for other plans; the limit is " + capacity);
        }
        reservations.put(r.key(), r);
        return new TeamAnswer(true, r.person(), load + 1, capacity, "held");
    }

    @Override
    public int release(ReleaseRequest req) {
        updates++;
        List<String> drop = new ArrayList<>();
        for (Reservation r : reservations.values()) {
            if (r.negotiationId().equals(req.negotiationId()) && !req.keep().contains(r.key())) {
                drop.add(r.key());
            }
        }
        drop.forEach(reservations::remove);
        return drop.size();
    }

    @Override
    public TeamSnapshot snapshot() {
        return new TeamSnapshot(team, capacity, workload == null ? Map.of() : Map.copyOf(workload),
                List.copyOf(reservations.values()), refreshedAt);
    }
}
