package dev.promisegraph.links;

import dev.promisegraph.domain.Promise;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a slipped promise does to the promises that depend on it.
 *
 * <p>Each dependent keeps the same gap it had to its upstream promise: if the API was due 13 Nov and
 * the dashboard 18 Nov, the dashboard is assumed to need those 5 days after the API, so an API moved
 * to 27 Nov makes the dashboard likely on 2 Dec. Without knowing how long the work really takes,
 * this is the cautious choice: any later upstream date flags the dependent, and an owner who has
 * slack simply keeps their date. A dependent whose upstream moved earlier is not flagged, and the
 * ripple stops at closed promises.
 */
public final class Ripple {

    public static final int DEFAULT_MAX_DEPTH = 5;
    static final int MAX_CYCLES = 5;

    /**
     * One affected promise.
     *
     * @param likelyDue null when the promise or its upstream has no date
     * @param via the upstream promise that delays it
     */
    public record Impact(String promiseId, String owner, String what, String due, String likelyDue, int depth, String via) {}

    /** @param cycles chains of promises that wait on each other, each starting and ending at the same promise */
    public record Result(String sourceId, String oldDue, String newDue, List<Impact> impacted, List<List<String>> cycles) {

        public Result {
            impacted = List.copyOf(impacted);
            cycles = cycles.stream().map(List::copyOf).toList();
        }
    }

    private Ripple() {}

    private static LocalDate date(String iso) {
        return iso == null ? null : LocalDate.parse(iso);
    }

    /**
     * @param dependents confirmed links as upstream promise id to the ids that depend on it
     * @param promises the promises reachable from the source, by id
     */
    public static Result compute(String sourceId, String oldDue, String newDue,
            Map<String, List<String>> dependents, Map<String, Promise> promises, int maxDepth) {
        Map<String, LocalDate> likely = new HashMap<>();
        Map<String, LocalDate> original = new HashMap<>();
        likely.put(sourceId, date(newDue));
        original.put(sourceId, date(oldDue));

        List<Impact> impacted = new ArrayList<>();
        Set<String> seen = new HashSet<>(Set.of(sourceId));
        Deque<String> queue = new ArrayDeque<>(List.of(sourceId));
        Map<String, Integer> depth = new HashMap<>(Map.of(sourceId, 0));

        while (!queue.isEmpty()) {
            String u = queue.removeFirst();
            int d = depth.get(u);
            if (d >= maxDepth) {
                continue;
            }
            LocalDate uLikely = likely.get(u);
            LocalDate uOriginal = original.get(u);
            for (String v : dependents.getOrDefault(u, List.of())) {
                if (!seen.add(v)) {
                    continue;
                }
                Promise p = promises.get(v);
                if (p == null || !p.stillOpen()) {
                    continue; // closed promises are no longer affected and stop the ripple
                }
                LocalDate vDue = date(p.due());
                LocalDate vLikely;
                boolean affected;
                if (vDue == null || uLikely == null) {
                    vLikely = null;
                    affected = true; // no dates to compare: tell the owner it may move
                } else {
                    vLikely = uOriginal != null
                            ? vDue.plusDays(ChronoUnit.DAYS.between(uOriginal, uLikely))
                            : uLikely.plusDays(1);
                    affected = vLikely.isAfter(vDue);
                }
                if (!affected) {
                    continue;
                }
                impacted.add(new Impact(v, p.owner(), p.what(), p.due(), vLikely == null ? null : vLikely.toString(), d + 1, u));
                likely.put(v, vLikely);
                original.put(v, vDue);
                depth.put(v, d + 1);
                queue.addLast(v);
            }
        }
        return new Result(sourceId, oldDue, newDue, impacted, cycles(sourceId, dependents, maxDepth + 1));
    }

    /** Dependency loops reachable from the source, found by depth-first search. */
    static List<List<String>> cycles(String sourceId, Map<String, List<String>> dependents, int maxDepth) {
        List<List<String>> found = new ArrayList<>();
        Set<String> reported = new HashSet<>();
        Deque<String> path = new ArrayDeque<>();
        Set<String> onPath = new LinkedHashSet<>();
        dfs(sourceId, dependents, path, onPath, new HashSet<>(), found, reported, maxDepth);
        return found;
    }

    private static void dfs(String node, Map<String, List<String>> dependents, Deque<String> path, Set<String> onPath,
            Set<String> done, List<List<String>> found, Set<String> reported, int maxDepth) {
        if (found.size() >= MAX_CYCLES || path.size() > maxDepth) {
            return;
        }
        path.addLast(node);
        onPath.add(node);
        for (String next : dependents.getOrDefault(node, List.of())) {
            if (onPath.contains(next)) {
                List<String> cycle = new ArrayList<>();
                boolean in = false;
                for (String n : path) {
                    in = in || n.equals(next);
                    if (in) {
                        cycle.add(n);
                    }
                }
                cycle.add(next);
                String key = canonical(cycle);
                if (reported.add(key)) {
                    found.add(cycle);
                }
            } else if (!done.contains(next)) {
                dfs(next, dependents, path, onPath, done, found, reported, maxDepth);
            }
        }
        path.removeLast();
        onPath.remove(node);
        done.add(node);
    }

    /** The same loop found from a different starting point is reported once. */
    private static String canonical(List<String> cycle) {
        List<String> ring = cycle.subList(0, cycle.size() - 1);
        int start = 0;
        for (int i = 1; i < ring.size(); i++) {
            if (ring.get(i).compareTo(ring.get(start)) < 0) {
                start = i;
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ring.size(); i++) {
            sb.append(ring.get((start + i) % ring.size())).append('|');
        }
        return sb.toString();
    }
}
