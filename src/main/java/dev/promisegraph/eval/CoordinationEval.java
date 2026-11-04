package dev.promisegraph.eval;

import dev.promisegraph.domain.Commitment;
import dev.promisegraph.domain.Matching;
import dev.promisegraph.domain.Matching.Resolution;
import dev.promisegraph.domain.Promise;
import dev.promisegraph.domain.PromiseStatus;
import dev.promisegraph.extraction.RuleBasedExtractor;
import dev.promisegraph.handoff.Absence;
import dev.promisegraph.handoff.AbsenceDetector;
import dev.promisegraph.handoff.Directory;
import dev.promisegraph.handoff.HandoffPlanning;
import dev.promisegraph.links.Link;
import dev.promisegraph.links.LinkSuggester;
import dev.promisegraph.links.Ripple;
import dev.promisegraph.sim.Company;
import dev.promisegraph.sim.Dataset;
import dev.promisegraph.sim.Dataset.LabelledMessage;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Gate 2: do links, ripples and handoff plans match what a person would decide? Run with
 * {@code ./gradlew evalGate2}.
 *
 * <p>Replays the 24 hand-written core-scenario messages in time order through the same logic the
 * workflows use, and compares against the expected answers below, written by hand from the story.
 * Suggested links are treated as confirmed, as a person would confirm the correct ones. The set is
 * small, so this gate guards against regressions; it does not prove accuracy on real messages.
 */
public final class CoordinationEval {

    /** Expected dependency links, as (upstream promise, dependent promise). */
    static final List<List<String>> EXPECTED_LINKS = List.of(
            List.of("ship the data export API", "build the export ingestion job"));

    /** When the export API slips, these promises are warned. */
    static final String RIPPLE_SOURCE = "ship the data export API";
    static final Set<String> EXPECTED_RIPPLE = Set.of("build the export ingestion job");

    /** Absences in the story: person, first day, last day. */
    static final List<List<String>> EXPECTED_ABSENCES = List.of(List.of("karan", "2026-11-16", "2026-11-27"));

    /** Who should cover each promise of someone going away. */
    static final Map<String, Map<String, String>> EXPECTED_COVER = Map.of(
            "karan", Map.of("write the launch FAQ for support", "nikhil"));

    public record Report(int linksFound, int linksCorrect, int linksExpected,
            Set<String> rippleFound, Set<String> rippleExpected,
            int absencesCorrect, int absencesFound, int absencesExpected,
            int coverCorrect, int coverExpected, List<String> notes) {

        public boolean passed() {
            return linksCorrect == linksExpected && linksFound == linksExpected
                    && rippleFound.equals(rippleExpected)
                    && absencesCorrect == absencesExpected && absencesFound == absencesExpected
                    && coverCorrect == coverExpected;
        }

        public String render() {
            StringBuilder sb = new StringBuilder();
            sb.append("links        %d of %d expected found, %d suggested in total%n".formatted(linksCorrect, linksExpected, linksFound));
            sb.append("ripple       warned %s, expected %s%n".formatted(rippleFound, rippleExpected));
            sb.append("absences     %d of %d expected found, %d detected in total%n".formatted(absencesCorrect, absencesExpected, absencesFound));
            sb.append("cover picks  %d of %d match%n".formatted(coverCorrect, coverExpected));
            notes.forEach(n -> sb.append("  ").append(n).append(System.lineSeparator()));
            return sb.toString();
        }
    }

    private CoordinationEval() {}

    private static Instant prepTime(Absence a, int prepDays) {
        return LocalDate.parse(a.startsOn()).atTime(4, 0).toInstant(ZoneOffset.UTC).minusSeconds(prepDays * 86_400L);
    }

    public static Report evaluate(List<LabelledMessage> rows, int prepDays) {
        RuleBasedExtractor extractor = new RuleBasedExtractor();
        Directory directory = new Directory(Company.PEOPLE);
        Map<String, Promise> promises = new LinkedHashMap<>();
        List<Link> links = new ArrayList<>();
        List<Absence> absences = new ArrayList<>();
        Set<String> rippled = new HashSet<>();
        Map<String, String> coverPicks = new HashMap<>(); // "person|promise what" -> owner
        Set<String> planned = new HashSet<>();
        List<String> notes = new ArrayList<>();

        for (LabelledMessage row : rows) {
            if (!row.message().id().startsWith("core-")) {
                continue;
            }
            Instant at = OffsetDateTime.parse(row.message().ts()).toInstant();
            // Plan any handoff whose preparation time has come, before handling this message.
            for (Absence a : absences) {
                if (!planned.contains(a.handoffId()) && !at.isBefore(prepTime(a, prepDays))) {
                    planned.add(a.handoffId());
                    plan(a, promises, links, absences, directory, coverPicks);
                }
            }
            AbsenceDetector.detect(row.message()).ifPresent(absences::add);

            for (Commitment c : extractor.extract(List.of(row.message()), Company.directory())) {
                List<Promise> open = promises.values().stream().filter(Promise::stillOpen).toList();
                Resolution r = Matching.resolve(c, open, "demo", row.message().ts(), promises.keySet());
                switch (r.action()) {
                    case START -> {
                        if (r.promise() != null) {
                            Promise p = r.promise().withStatus(PromiseStatus.ACTIVE);
                            LinkSuggester.suggest(p, c.quote(), open).ifPresent(s -> links.add(
                                    new Link("demo", s.upstreamId(), p.id(), Link.Status.CONFIRMED, s.clause(), "rules", "eval", row.message().ts())));
                            promises.put(p.id(), p);
                        }
                    }
                    case KEPT -> promises.computeIfPresent(r.promiseId(), (k, p) -> p.withStatus(PromiseStatus.KEPT));
                    case SLIP -> {
                        Promise before = promises.get(r.promiseId());
                        promises.put(r.promiseId(), before.withDue(r.due()).withStatus(PromiseStatus.AT_RISK));
                        if (before.what().equals(RIPPLE_SOURCE)) {
                            Map<String, List<String>> dependents = new HashMap<>();
                            links.forEach(l -> dependents.computeIfAbsent(l.from(), k -> new ArrayList<>()).add(l.to()));
                            Ripple.Result result = Ripple.compute(before.id(), before.due(), r.due(), dependents, promises, Ripple.DEFAULT_MAX_DEPTH);
                            result.impacted().forEach(i -> rippled.add(i.what()));
                            notes.add("ripple: " + RIPPLE_SOURCE + " " + before.due() + " -> " + r.due() + " warns "
                                    + result.impacted().stream().map(i -> i.what() + " (likely " + i.likelyDue() + ")").toList());
                        }
                    }
                    default -> { }
                }
            }
        }
        for (Absence a : absences) {
            if (!planned.contains(a.handoffId())) {
                plan(a, promises, links, absences, directory, coverPicks);
            }
        }

        int linksCorrect = 0;
        for (List<String> expected : EXPECTED_LINKS) {
            boolean found = links.stream().anyMatch(l -> promises.get(l.from()).what().equals(expected.get(0))
                    && promises.get(l.to()).what().equals(expected.get(1)));
            linksCorrect += found ? 1 : 0;
            if (!found) {
                notes.add("missing link: " + expected.get(1) + " depends on " + expected.get(0));
            }
        }
        for (Link l : links) {
            notes.add("link: " + promises.get(l.to()).what() + " depends on " + promises.get(l.from()).what() + " (\"" + l.reason() + "\")");
        }
        int absencesCorrect = 0;
        for (List<String> e : EXPECTED_ABSENCES) {
            absencesCorrect += absences.stream().anyMatch(a -> a.person().equals(e.get(0)) && a.startsOn().equals(e.get(1))
                    && e.get(2).equals(a.endsOn())) ? 1 : 0;
        }
        int coverExpected = 0;
        int coverCorrect = 0;
        for (var person : EXPECTED_COVER.entrySet()) {
            for (var item : person.getValue().entrySet()) {
                coverExpected++;
                String got = coverPicks.get(person.getKey() + "|" + item.getKey());
                if (item.getValue().equals(got)) {
                    coverCorrect++;
                }
                notes.add("cover: " + item.getKey() + " -> " + got + " (expected " + item.getValue() + ")");
            }
        }
        return new Report(links.size(), linksCorrect, EXPECTED_LINKS.size(), rippled, EXPECTED_RIPPLE,
                absencesCorrect, absences.size(), EXPECTED_ABSENCES.size(), coverCorrect, coverExpected, notes);
    }

    private static void plan(Absence a, Map<String, Promise> promises, List<Link> links, List<Absence> absences,
            Directory directory, Map<String, String> coverPicks) {
        List<Promise> items = promises.values().stream().filter(p -> p.owner().equals(a.person()) && p.stillOpen()).toList();
        Map<String, Integer> workload = new HashMap<>();
        promises.values().stream().filter(Promise::stillOpen).forEach(p -> workload.merge(p.owner(), 1, Integer::sum));
        Map<String, List<Promise>> dependsOn = new HashMap<>();
        Map<String, List<Promise>> blocks = new HashMap<>();
        for (Link l : links) {
            dependsOn.computeIfAbsent(l.to(), k -> new ArrayList<>()).add(promises.get(l.from()));
            blocks.computeIfAbsent(l.from(), k -> new ArrayList<>()).add(promises.get(l.to()));
        }
        Set<String> away = new HashSet<>();
        absences.stream().filter(o -> !o.person().equals(a.person()) && o.overlaps(a.startsOn(), a.endsOn())).forEach(o -> away.add(o.person()));
        var ctx = new HandoffPlanning.Context(a.handoffId(), a.person(), a.startsOn(), a.endsOn(), items, dependsOn, blocks,
                workload, away, directory);
        for (HandoffPlanning.Item i : new HandoffPlanning.RulePlanner().plan(ctx).items()) {
            coverPicks.put(a.person() + "|" + i.what(), i.proposedOwner());
        }
    }

    public static void main(String[] args) {
        Report report = evaluate(Dataset.load("demo"), 3);
        System.out.print(report.render());
        if (!report.passed()) {
            System.out.println("\nGATE 2 FAILED");
            System.exit(1);
        }
        System.out.println("\nGATE 2 PASSED");
    }
}
