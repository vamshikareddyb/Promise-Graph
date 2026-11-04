package dev.promisegraph.recovery;

import dev.promisegraph.domain.Person;
import dev.promisegraph.domain.Promise;
import dev.promisegraph.handoff.Directory;
import dev.promisegraph.links.Ripple;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Everything a recovery planner and the verifier know about one slip, loaded fresh from storage.
 *
 * @param today the workflow's current date, so plans never move anything into the past
 * @param source the promise that slipped, as it is now
 * @param oldDue its date before the slip
 * @param newDue its date after the slip
 * @param impacts the dependents the ripple flagged, with their likely new dates
 * @param promises the source and every impacted promise, by id, as they are now
 * @param dependents confirmed links among those promises: upstream id to dependent ids
 * @param workload open promises per person
 * @param unavailable people away before the new date, who can't be asked to help
 */
public record Situation(
        String tenant,
        String negotiationId,
        String today,
        Promise source,
        String oldDue,
        String newDue,
        List<Ripple.Impact> impacts,
        Map<String, Promise> promises,
        Map<String, List<String>> dependents,
        Map<String, Integer> workload,
        Set<String> unavailable,
        Directory directory,
        RecoverySettings settings) {

    public Optional<Promise> promise(String id) {
        return Optional.ofNullable(promises.get(id));
    }

    public Optional<Ripple.Impact> impact(String promiseId) {
        return impacts.stream().filter(i -> i.promiseId().equals(promiseId)).findFirst();
    }

    public boolean customerFacing(Promise p) {
        return settings.customerFacing(p, directory);
    }

    public Optional<String> teamOf(String person) {
        return directory.person(person).map(Person::team);
    }

    /** The promises this one waits on, among the ones in this situation. */
    public List<String> upstreams(String promiseId) {
        List<String> out = new ArrayList<>();
        dependents.forEach((from, tos) -> {
            if (tos.contains(promiseId)) {
                out.add(from);
            }
        });
        return out;
    }

    public int workloadOf(String person) {
        return workload.getOrDefault(person, 0);
    }

    /** Teams whose promises are affected, including the source owner's. People outside the directory count as their own team. */
    public Set<String> teamsAffected() {
        Set<String> teams = new TreeSet<>();
        teams.add(teamOf(source.owner()).orElse("person:" + source.owner()));
        for (Ripple.Impact i : impacts) {
            teams.add(teamOf(i.owner()).orElse("person:" + i.owner()));
        }
        return teams;
    }

    public boolean customerAtRisk() {
        return impacts.stream().map(i -> promises.get(i.promiseId())).anyMatch(p -> p != null && customerFacing(p));
    }

    /**
     * Who approves a plan: the leads of every affected team. Someone outside the directory, or a
     * lead who is away, is replaced by the owner themselves. Sorted, so the order is stable.
     */
    public List<String> approvers() {
        Set<String> out = new TreeSet<>();
        List<String> owners = new ArrayList<>();
        owners.add(source.owner());
        impacts.forEach(i -> owners.add(i.owner()));
        for (String owner : owners) {
            String lead = directory.leadOf(owner).filter(l -> !unavailable.contains(l)).orElse(owner);
            out.add(lead);
        }
        return List.copyOf(out);
    }

    /** Everyone whose promise appears in the situation. */
    public Set<String> owners() {
        Set<String> out = new TreeSet<>();
        out.add(source.owner());
        impacts.forEach(i -> out.add(i.owner()));
        return out;
    }
}
