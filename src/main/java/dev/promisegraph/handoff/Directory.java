package dev.promisegraph.handoff;

import dev.promisegraph.domain.Person;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Who works with whom. Phase 2 uses the simulated company; a directory sync replaces it later. */
public final class Directory {

    private final Map<String, Person> byId = new LinkedHashMap<>();

    public Directory(List<Person> people) {
        people.forEach(p -> byId.put(p.id(), p));
    }

    public Optional<Person> person(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    public List<Person> people() {
        return List.copyOf(byId.values());
    }

    /** Everyone else in the same team; empty for people outside the directory, such as email contacts. */
    public List<Person> teammates(String id) {
        Person p = byId.get(id);
        if (p == null) {
            return List.of();
        }
        return byId.values().stream().filter(o -> o.team().equals(p.team()) && !o.id().equals(id)).toList();
    }

    /** The lead of this person's team; for a lead, the lead themselves. */
    public Optional<String> leadOf(String id) {
        Person p = byId.get(id);
        if (p == null) {
            return Optional.empty();
        }
        return byId.values().stream().filter(o -> o.team().equals(p.team()) && o.lead()).map(Person::id).findFirst();
    }
}
