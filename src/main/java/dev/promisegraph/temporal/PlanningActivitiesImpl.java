package dev.promisegraph.temporal;

import dev.promisegraph.domain.Promise;
import dev.promisegraph.domain.PromiseStatus;
import dev.promisegraph.handoff.Absence;
import dev.promisegraph.handoff.Directory;
import dev.promisegraph.handoff.HandoffPlanning;
import dev.promisegraph.links.Link;
import dev.promisegraph.store.HandoffStore;
import dev.promisegraph.store.LinkStore;
import dev.promisegraph.store.Repository;
import dev.promisegraph.temporal.Contracts.PlanRequest;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Gathers the facts a handoff planner needs, then runs the configured planner (rules or agent). */
public final class PlanningActivitiesImpl implements PlanningActivities {

    private final HandoffPlanning.Planner planner;
    private final Repository repo;
    private final LinkStore links;
    private final HandoffStore handoffs;
    private final Directory directory;

    public PlanningActivitiesImpl(HandoffPlanning.Planner planner, Repository repo, LinkStore links, HandoffStore handoffs, Directory directory) {
        this.planner = planner;
        this.repo = repo;
        this.links = links;
        this.handoffs = handoffs;
        this.directory = directory;
    }

    @Override
    public HandoffPlanning.Plan planHandoff(PlanRequest req) {
        return planner.plan(context(req));
    }

    HandoffPlanning.Context context(PlanRequest req) {
        String tenant = req.tenant();
        List<Promise> items = repo.openPromises(tenant, List.of(req.person()));
        Set<String> ids = new HashSet<>();
        items.forEach(p -> ids.add(p.id()));

        Map<String, List<Promise>> dependsOn = new HashMap<>();
        Map<String, List<Promise>> blocks = new HashMap<>();
        for (Link l : links.linksOf(tenant, ids)) {
            if (l.status() != Link.Status.CONFIRMED) {
                continue;
            }
            if (ids.contains(l.to())) {
                repo.getPromise(tenant, l.from()).ifPresent(p -> dependsOn.computeIfAbsent(l.to(), k -> new ArrayList<>()).add(p));
            }
            if (ids.contains(l.from())) {
                repo.getPromise(tenant, l.to()).ifPresent(p -> blocks.computeIfAbsent(l.from(), k -> new ArrayList<>()).add(p));
            }
        }

        Map<String, Integer> workload = new HashMap<>();
        for (Promise p : repo.promisesByStatus(tenant, EnumSet.of(PromiseStatus.DETECTED, PromiseStatus.ACTIVE, PromiseStatus.AT_RISK))) {
            workload.merge(p.owner(), 1, Integer::sum);
        }

        Set<String> unavailable = new HashSet<>();
        for (Absence a : handoffs.absences(tenant)) {
            if (!a.person().equals(req.person()) && a.overlaps(req.startsOn(), req.endsOn())) {
                unavailable.add(a.person());
            }
        }
        return new HandoffPlanning.Context(req.handoffId(), req.person(), req.startsOn(), req.endsOn(), items,
                dependsOn, blocks, workload, unavailable, directory);
    }
}
