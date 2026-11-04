package dev.promisegraph.recovery;

import dev.promisegraph.domain.Promise;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks a recovery plan against hard rules before anyone sees it, whoever proposed it. A plan
 * that breaks any rule is dropped, with the reasons kept for the record.
 *
 * <p>The rules: every move is about an open promise in this slip; dates are real and not in the
 * past; nobody's date is pulled earlier than they promised; the late promise only comes forward
 * if someone is added to help; nobody goes over the workload limit or is asked while away; and no
 * promise ends up due before the work it waits on, unless it delivers a first part.
 */
public final class PlanVerifier {

    public static final int MAX_MOVES = 15;

    private PlanVerifier() {}

    public static RecoveryPlan verify(RecoveryPlan plan, Situation s) {
        return plan.verified(score(plan, s), check(plan, s));
    }

    private static LocalDate date(String iso) {
        if (iso == null) {
            return null;
        }
        try {
            return LocalDate.parse(iso);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    static List<String> check(RecoveryPlan plan, Situation s) {
        Set<String> problems = new LinkedHashSet<>();
        if (plan.title() == null || plan.title().isBlank()) {
            problems.add("the plan has no title");
        }
        if (plan.moves().isEmpty()) {
            problems.add("the plan changes nothing");
        }
        if (plan.moves().size() > MAX_MOVES) {
            problems.add("the plan has more than " + MAX_MOVES + " moves");
        }
        LocalDate today = LocalDate.parse(s.today());
        String sourceId = s.source().id();

        Map<String, LocalDate> finalDue = new HashMap<>();
        s.promises().forEach((id, p) -> finalDue.put(id, date(p.due())));
        Set<String> dated = new HashSet<>();
        Set<String> partial = new HashSet<>();
        Set<String> reassigned = new HashSet<>();
        Set<String> helped = new HashSet<>();
        Map<String, Integer> added = new LinkedHashMap<>();

        for (Move m : plan.moves()) {
            if (!Move.knownKind(m.kind())) {
                problems.add("unknown kind of move: " + m.kind());
                continue;
            }
            Promise p = s.promise(m.promiseId()).orElse(null);
            if (p == null) {
                problems.add("\"" + (m.what() == null ? m.promiseId() : m.what()) + "\" is not one of the promises affected by this slip");
                continue;
            }
            String name = "\"" + p.what() + "\"";
            if (!p.stillOpen()) {
                problems.add(name + " is already closed");
            }
            switch (m.kind()) {
                case Move.MOVE_DATE, Move.KEEP_DATE, Move.DELIVER_PARTIAL -> {
                    if (!dated.add(p.id())) {
                        problems.add(name + " has more than one date change");
                    }
                    if (Move.DELIVER_PARTIAL.equals(m.kind())) {
                        if (p.id().equals(sourceId)) {
                            problems.add("a first part is for work waiting on " + name + ", not for " + name + " itself");
                        } else if (p.due() == null) {
                            problems.add(name + " has no date to keep");
                        }
                        partial.add(p.id());
                    }
                    if (Move.MOVE_DATE.equals(m.kind())) {
                        LocalDate to = date(m.toDue());
                        if (to == null) {
                            problems.add(name + " gets an invalid date: " + m.toDue());
                            continue;
                        }
                        if (to.isBefore(today)) {
                            problems.add(name + " would be due in the past (" + to + ")");
                        }
                        LocalDate original = date(p.id().equals(sourceId) ? s.oldDue() : p.due());
                        if (original != null && to.isBefore(original)) {
                            problems.add(p.id().equals(sourceId)
                                    ? name + " would be due before its original date " + original
                                    : name + " would be pulled earlier than " + p.owner() + " promised (" + original + ")");
                        }
                        finalDue.put(p.id(), to);
                    }
                }
                case Move.ADD_HELPER, Move.REASSIGN -> {
                    String who = m.person() == null ? "" : m.person().strip();
                    if (who.isEmpty()) {
                        problems.add("a move on " + name + " names nobody");
                        continue;
                    }
                    if (!s.directory().people().isEmpty() && s.directory().person(who).isEmpty()) {
                        problems.add(who + " is not in the directory");
                        continue;
                    }
                    if (who.equals(p.owner())) {
                        problems.add(who + " already owns " + name);
                        continue;
                    }
                    if (s.unavailable().contains(who)) {
                        problems.add(who + " is away before the new date");
                        continue;
                    }
                    if (Move.REASSIGN.equals(m.kind()) && !reassigned.add(p.id())) {
                        problems.add(name + " is given to more than one person");
                    }
                    helped.add(p.id());
                    added.merge(who, 1, Integer::sum);
                }
                default -> { }
            }
        }

        for (var e : added.entrySet()) {
            int load = s.workloadOf(e.getKey()) + e.getValue();
            if (load > s.settings().capacity()) {
                problems.add(e.getKey() + " would have " + load + " open promises; the limit is " + s.settings().capacity());
            }
        }

        LocalDate current = date(s.source().due());
        LocalDate planned = finalDue.get(sourceId);
        if (current != null && planned != null && planned.isBefore(current) && !helped.contains(sourceId)) {
            problems.add("\"" + s.source().what() + "\" comes forward without anyone added to help");
        }

        s.dependents().forEach((up, downs) -> {
            for (String down : downs) {
                if (partial.contains(down) || !s.promises().containsKey(up) || !s.promises().containsKey(down)) {
                    continue;
                }
                LocalDate u = finalDue.get(up);
                LocalDate d = finalDue.get(down);
                if (u != null && d != null && d.isBefore(u)) {
                    problems.add("\"" + s.promises().get(down).what() + "\" (due " + d + ") would be due before \""
                            + s.promises().get(up).what() + "\" (due " + u + "), which it waits on");
                }
            }
        });
        return new ArrayList<>(problems);
    }

    static RecoveryPlan.Score score(RecoveryPlan plan, Situation s) {
        String sourceId = s.source().id();
        Map<String, LocalDate> finalDue = new HashMap<>();
        s.promises().forEach((id, p) -> finalDue.put(id, date(p.due())));
        int moved = 0;
        int customer = 0;
        int partials = 0;
        int people = 0;
        for (Move m : plan.moves()) {
            Promise p = s.promises().get(m.promiseId());
            if (p == null) {
                continue;
            }
            if (m.changesDate()) {
                LocalDate to = date(m.toDue());
                if (to != null) {
                    finalDue.put(p.id(), to);
                    LocalDate from = date(p.due());
                    if (!p.id().equals(sourceId) && from != null && to.isAfter(from)) {
                        moved++;
                        customer += s.customerFacing(p) ? 1 : 0;
                    }
                }
            } else if (Move.DELIVER_PARTIAL.equals(m.kind())) {
                partials++;
            } else if (m.addsWork()) {
                people++;
            }
        }
        int late = 0;
        for (var e : s.promises().entrySet()) {
            LocalDate original = date(e.getKey().equals(sourceId) ? s.oldDue() : e.getValue().due());
            LocalDate end = finalDue.get(e.getKey());
            if (original != null && end != null && end.isAfter(original)) {
                late += (int) ChronoUnit.DAYS.between(original, end);
            }
        }
        return new RecoveryPlan.Score(moved, customer, partials, people, late);
    }
}
