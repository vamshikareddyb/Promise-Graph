package dev.promisegraph.handoff;

import dev.promisegraph.domain.Person;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Who works with whom: the simulated company in the demo, or the People page in team mode, read
 * fresh on every use so an admin's edits apply at once.
 */
public final class Directory {

    private final Supplier<List<Person>> source;
    private final Supplier<List<String>> admins;

    public Directory(List<Person> people) {
        List<Person> fixed = List.copyOf(people);
        this.source = () -> fixed;
        this.admins = List::of;
    }

    public Directory(Supplier<List<Person>> source) {
        this(source, List::of);
    }

    /** @param admins who runs the app: they decide handoffs for people who have no lead, or are the lead */
    public Directory(Supplier<List<Person>> source, Supplier<List<String>> admins) {
        this.source = source;
        this.admins = admins;
    }

    public List<String> admins() {
        return List.copyOf(admins.get());
    }

    private Map<String, Person> byId() {
        Map<String, Person> byId = new LinkedHashMap<>();
        source.get().forEach(p -> byId.put(p.id(), p));
        return byId;
    }

    public Optional<Person> person(String id) {
        return Optional.ofNullable(byId().get(id));
    }

    public List<Person> people() {
        return List.copyOf(byId().values());
    }

    /** Everyone else in the same team; empty for people outside the directory, such as email contacts. */
    public List<Person> teammates(String id) {
        Map<String, Person> byId = byId();
        Person p = byId.get(id);
        if (p == null) {
            return List.of();
        }
        return byId.values().stream().filter(o -> o.team().equals(p.team()) && !o.id().equals(id)).toList();
    }

    /** The lead of this person's team; for a lead, the lead themselves. */
    public Optional<String> leadOf(String id) {
        Map<String, Person> byId = byId();
        Person p = byId.get(id);
        if (p == null) {
            return Optional.empty();
        }
        return byId.values().stream().filter(o -> o.team().equals(p.team()) && o.lead()).map(Person::id).findFirst();
    }
}
