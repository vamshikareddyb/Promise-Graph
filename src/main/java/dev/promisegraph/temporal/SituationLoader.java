package dev.promisegraph.temporal;

import dev.promisegraph.domain.Promise;
import dev.promisegraph.domain.PromiseStatus;
import dev.promisegraph.handoff.Absence;
import dev.promisegraph.handoff.Directory;
import dev.promisegraph.links.Link;
import dev.promisegraph.links.Ripple;
import dev.promisegraph.recovery.RecoverySettings;
import dev.promisegraph.recovery.Situation;
import dev.promisegraph.store.HandoffStore;
import dev.promisegraph.store.LinkStore;
import dev.promisegraph.store.Repository;
import dev.promisegraph.temporal.Contracts.RippleRequest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Loads a {@link Situation} from storage, fresh each time, for the recovery planners and the verifier. */
public final class SituationLoader {

    private final Repository repo;
    private final LinkStore links;
    private final HandoffStore handoffs;
    private final Directory directory;
    private final RecoverySettings settings;

    public SituationLoader(Repository repo, LinkStore links, HandoffStore handoffs, Directory directory, RecoverySettings settings) {
        this.repo = repo;
        this.links = links;
        this.handoffs = handoffs;
        this.directory = directory;
        this.settings = settings;
    }

    public Directory directory() {
        return directory;
    }

    public RecoverySettings settings() {
        return settings;
    }

    /** Empty if the late promise no longer exists. */
    public Optional<Situation> load(String negotiationId, RippleRequest ripple, Ripple.Result result, String today) {
        String tenant = ripple.tenant();
        Optional<Promise> source = repo.getPromise(tenant, ripple.promiseId());
        if (source.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Promise> promises = new LinkedHashMap<>();
        promises.put(source.get().id(), source.get());
        for (Ripple.Impact i : result.impacted()) {
            repo.getPromise(tenant, i.promiseId()).ifPresent(p -> promises.put(p.id(), p));
        }

        Map<String, List<String>> dependents = new HashMap<>();
        for (Link l : links.confirmedFrom(tenant, promises.keySet())) {
            if (promises.containsKey(l.to())) {
                dependents.computeIfAbsent(l.from(), k -> new ArrayList<>()).add(l.to());
            }
        }

        Map<String, Integer> workload = new HashMap<>();
        for (Promise p : repo.promisesByStatus(tenant, EnumSet.of(PromiseStatus.DETECTED, PromiseStatus.ACTIVE, PromiseStatus.AT_RISK))) {
            workload.merge(p.owner(), 1, Integer::sum);
        }

        String until = ripple.newDue() != null ? ripple.newDue() : LocalDate.parse(today).plusDays(14).toString();
        Set<String> unavailable = new HashSet<>();
        for (Absence a : handoffs.absences(tenant)) {
            if (a.overlaps(today, until)) {
                unavailable.add(a.person());
            }
        }
        return Optional.of(new Situation(tenant, negotiationId, today, source.get(), ripple.oldDue(), ripple.newDue(),
                result.impacted(), promises, dependents, workload, unavailable, directory, settings));
    }

    /** Open promises per member of a team; every member is listed, with zero if they have none. */
    public Map<String, Integer> teamWorkload(String tenant, String team) {
        Map<String, Integer> out = new LinkedHashMap<>();
        Set<String> members = new LinkedHashSet<>();
        directory.people().stream().filter(p -> p.team().equals(team)).forEach(p -> members.add(p.id()));
        members.forEach(m -> out.put(m, 0));
        for (Promise p : repo.openPromises(tenant, members)) {
            out.merge(p.owner(), 1, Integer::sum);
        }
        return out;
    }
}
