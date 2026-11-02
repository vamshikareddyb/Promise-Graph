package dev.promisegraph.sim;

import dev.promisegraph.domain.Person;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The simulated company used for development, demos and evaluation: 20 people in 4 teams.
 * Its 300 labelled messages live in {@code src/main/resources/sim/messages.jsonl}.
 */
public final class Company {

    public static final List<String> TEAMS = List.of("platform", "analytics", "customer-success", "product");

    public static final List<Person> PEOPLE = List.of(
            new Person("ravi", "Ravi Menon", "platform", "Engineering lead", true),
            new Person("neha", "Neha Kapoor", "platform", "Backend engineer", false),
            new Person("arjun", "Arjun Iyer", "platform", "Backend engineer", false),
            new Person("sara", "Sara Thomas", "platform", "SRE", false),
            new Person("kabir", "Kabir Shah", "platform", "Frontend engineer", false),
            new Person("meera", "Meera Rao", "analytics", "Analytics lead", true),
            new Person("dev", "Dev Malhotra", "analytics", "Data engineer", false),
            new Person("ananya", "Ananya Gupta", "analytics", "Data scientist", false),
            new Person("rahul", "Rahul Verma", "analytics", "Analyst", false),
            new Person("isha", "Isha Nair", "analytics", "Data engineer", false),
            new Person("priya", "Priya Sharma", "customer-success", "Customer success lead", true),
            new Person("vikram", "Vikram Singh", "customer-success", "Account manager", false),
            new Person("leela", "Leela Joseph", "customer-success", "Account manager", false),
            new Person("omar", "Omar Khan", "customer-success", "Support engineer", false),
            new Person("tara", "Tara Das", "customer-success", "Onboarding specialist", false),
            new Person("aditi", "Aditi Bose", "product", "Head of product", true),
            new Person("karan", "Karan Mehta", "product", "Product manager", false),
            new Person("zoya", "Zoya Ali", "product", "Product designer", false),
            new Person("nikhil", "Nikhil Joshi", "product", "Product manager", false),
            new Person("ritu", "Ritu Pillai", "product", "UX researcher", false));

    private Company() {}

    /** Phase 1 uses the simulated company as its directory. A directory sync replaces this later. */
    public static Set<String> directory() {
        return PEOPLE.stream().map(Person::id).collect(Collectors.toUnmodifiableSet());
    }
}
