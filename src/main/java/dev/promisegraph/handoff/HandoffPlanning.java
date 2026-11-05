package dev.promisegraph.handoff;

import dev.promisegraph.domain.Evidence;
import dev.promisegraph.domain.Person;
import dev.promisegraph.domain.Promise;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** The facts a handoff planner works from, and the plan it produces. Shared by the rule planner and the agent. */
public final class HandoffPlanning {

    public static final int MAX_BRIEF_LENGTH = 600;

    private HandoffPlanning() {}

    /**
     * @param items the open promises of the person leaving
     * @param dependsOn per item, the promises it waits on
     * @param blocks per item, the promises waiting on it
     * @param workload open promises per person
     * @param unavailable people also away during this window
     */
    public record Context(
            String handoffId,
            String person,
            String startsOn,
            String endsOn,
            List<Promise> items,
            Map<String, List<Promise>> dependsOn,
            Map<String, List<Promise>> blocks,
            Map<String, Integer> workload,
            Set<String> unavailable,
            Directory directory) {

        public Optional<Promise> item(String promiseId) {
            return items.stream().filter(p -> p.id().equals(promiseId)).findFirst();
        }
    }

    public record Candidate(String person, double score, List<String> reasons, int openPromises) {}

    /** @param proposedOwner null when nobody suitable is available; the lead then decides */
    public record Item(String promiseId, String what, String due, String brief, String proposedOwner, String reason, List<String> candidates) {}

    /** @param decider who assigns the promises and approves the plan: the team lead, else an admin, else the person */
    public record Plan(String handoffId, String person, List<Item> items, String planner, String decider) {

        public Plan {
            items = List.copyOf(items);
        }
    }

    public interface Planner {
        Plan plan(Context ctx);
    }

    /**
     * Who assigns the person's promises: their team lead; for a lead, or someone with no lead, an
     * admin; and only when there is nobody else, the person themselves.
     */
    public static String decider(Context ctx) {
        return ctx.directory().leadOf(ctx.person()).filter(l -> !l.equals(ctx.person()) && !ctx.unavailable().contains(l))
                .or(() -> ctx.directory().admins().stream().filter(a -> !a.equals(ctx.person()) && !ctx.unavailable().contains(a)).findFirst())
                .orElse(ctx.person());
    }

    /** Ranks who could cover one promise. People away during the handoff are never suggested. */
    public static List<Candidate> rankCandidates(Context ctx, Promise item) {
        Map<String, List<String>> reasons = new LinkedHashMap<>();
        Optional<Person> leaving = ctx.directory().person(ctx.person());
        for (Person p : ctx.directory().teammates(ctx.person())) {
            reasons.computeIfAbsent(p.id(), k -> new ArrayList<>()).add("same team");
            if (leaving.isPresent() && p.role().equals(leaving.get().role())) {
                reasons.get(p.id()).add("same role");
            }
        }
        Set<String> linked = new LinkedHashSet<>();
        ctx.dependsOn().getOrDefault(item.id(), List.of()).forEach(p -> linked.add(p.owner()));
        ctx.blocks().getOrDefault(item.id(), List.of()).forEach(p -> linked.add(p.owner()));
        for (String owner : linked) {
            reasons.computeIfAbsent(owner, k -> new ArrayList<>()).add("works on a linked promise");
        }
        List<Candidate> out = new ArrayList<>();
        for (var e : reasons.entrySet()) {
            String id = e.getKey();
            if (id.equals(ctx.person()) || ctx.unavailable().contains(id)) {
                continue;
            }
            int load = ctx.workload().getOrDefault(id, 0);
            double score = 0;
            for (String r : e.getValue()) {
                score += switch (r) {
                    case "same role" -> 3;
                    case "same team" -> 2;
                    default -> 2;
                };
            }
            if (ctx.directory().person(id).map(Person::lead).orElse(false)) {
                score -= 1; // leads decide escalations; prefer to keep them free
            }
            score -= 0.5 * load;
            List<String> rs = new ArrayList<>(e.getValue());
            rs.add(load + " open promise" + (load == 1 ? "" : "s"));
            out.add(new Candidate(id, score, rs, load));
        }
        out.sort(Comparator.comparingDouble(Candidate::score).reversed().thenComparing(Candidate::person));
        return out;
    }

    /** A short factual brief for whoever covers the promise. */
    public static String brief(Context ctx, Promise p) {
        StringBuilder sb = new StringBuilder();
        sb.append(p.what());
        if (p.recipient() != null) {
            sb.append(" (promised to ").append(p.recipient()).append(')');
        }
        sb.append(". Due ").append(p.due() == null ? "with no date set" : p.due()).append(". Status: ")
                .append(p.status().dbValue().replace('_', ' ')).append('.');
        if (!p.evidence().isEmpty()) {
            Evidence last = p.evidence().get(p.evidence().size() - 1);
            sb.append(" Latest: \"").append(last.quote()).append("\"");
        }
        List<Promise> deps = ctx.dependsOn().getOrDefault(p.id(), List.of());
        if (!deps.isEmpty()) {
            sb.append(" Waiting on: ");
            sb.append(String.join("; ", deps.stream().map(d -> d.what() + " (" + d.owner() + ", due " + d.due() + ")").toList()));
            sb.append('.');
        }
        List<Promise> blocks = ctx.blocks().getOrDefault(p.id(), List.of());
        if (!blocks.isEmpty()) {
            sb.append(" Others waiting on it: ");
            sb.append(String.join("; ", blocks.stream().map(d -> d.what() + " (" + d.owner() + ")").toList()));
            sb.append('.');
        }
        String s = sb.toString();
        return s.length() > MAX_BRIEF_LENGTH ? s.substring(0, MAX_BRIEF_LENGTH - 1) + "…" : s;
    }

    /** Deterministic planner: the highest-ranked available person covers each promise. */
    public static final class RulePlanner implements Planner {

        @Override
        public Plan plan(Context ctx) {
            List<Item> items = new ArrayList<>();
            for (Promise p : ctx.items()) {
                List<Candidate> ranked = rankCandidates(ctx, p);
                Candidate top = ranked.isEmpty() ? null : ranked.get(0);
                items.add(new Item(p.id(), p.what(), p.due(), brief(ctx, p),
                        top == null ? null : top.person(),
                        top == null ? "nobody available; the lead decides" : String.join(", ", top.reasons()),
                        ranked.stream().map(Candidate::person).toList()));
            }
            return new Plan(ctx.handoffId(), ctx.person(), items, "rules", decider(ctx));
        }
    }
}
