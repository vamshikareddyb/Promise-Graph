package dev.promisegraph.recovery;

import dev.promisegraph.domain.Person;
import dev.promisegraph.domain.Promise;
import dev.promisegraph.links.Ripple;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Proposes recovery plans from three simple ideas, so every slip gets options without a model:
 *
 * <ol>
 *   <li><b>Accept the new dates.</b> Every affected promise moves to its likely date.
 *   <li><b>Add a helper.</b> A free teammate of the late owner helps win back half the delay;
 *       dependents move only by what is left.
 *   <li><b>Protect the customer dates.</b> Promises made to customers keep their date by delivering
 *       a first part; internal ones move. Also offered together with a helper.
 * </ol>
 *
 * <p>Winning back half the delay with one extra person is an assumption, stated in the plan for the
 * approvers to judge; nothing here knows how long the work really takes.
 */
public final class RulePlanner implements RecoveryPlanning.Planner {

    @Override
    public RecoveryPlanning.Candidates propose(Situation s, RecoveryPlanning.Feedback f) {
        List<RecoveryPlan> out = new ArrayList<>();
        if (s.newDue() == null) {
            return new RecoveryPlanning.Candidates(out, "rules");
        }
        List<String> helpers = rankHelpers(s, f.excludedHelpers());
        out.add(acceptDates(s));
        out.add(helpers.isEmpty() ? null : withHelper(s, helpers.get(0)));
        out.add(protectCustomers(s, null));
        out.add(helpers.isEmpty() ? null : protectCustomers(s, helpers.get(0)));
        out.add(helpers.size() < 2 ? null : withHelper(s, helpers.get(1)));
        return new RecoveryPlanning.Candidates(out.stream().filter(Objects::nonNull).toList(), "rules");
    }

    /** Teammates of the late owner who could help: not away, under the limit; same role first, then the lightest load. */
    static List<String> rankHelpers(Situation s, List<String> excluded) {
        Optional<Person> owner = s.directory().person(s.source().owner());
        String role = owner.map(Person::role).orElse("");
        return s.directory().teammates(s.source().owner()).stream()
                .filter(p -> !excluded.contains(p.id()) && !s.unavailable().contains(p.id()))
                .filter(p -> s.workloadOf(p.id()) + 1 <= s.settings().capacity())
                .sorted(Comparator.comparing((Person p) -> p.role().equals(role) ? 0 : 1)
                        .thenComparingInt(p -> s.workloadOf(p.id()))
                        .thenComparing(Person::id))
                .map(Person::id)
                .toList();
    }

    /** Where each dependent lands if the source finishes on {@code sourceDue}. */
    static List<Ripple.Impact> impactsFor(Situation s, String sourceDue) {
        return Ripple.compute(s.source().id(), s.oldDue(), sourceDue, s.dependents(), s.promises(), Ripple.DEFAULT_MAX_DEPTH).impacted();
    }

    private static boolean later(String a, String b) {
        return a != null && b != null && LocalDate.parse(a).isAfter(LocalDate.parse(b));
    }

    static RecoveryPlan acceptDates(Situation s) {
        List<Move> moves = new ArrayList<>();
        for (Ripple.Impact i : impactsFor(s, s.newDue())) {
            Promise p = s.promises().get(i.promiseId());
            if (p != null && later(i.likelyDue(), p.due())) {
                moves.add(Move.moveDate(p, i.likelyDue(), "keeps the same time after the work it waits on"));
            }
        }
        if (moves.isEmpty()) {
            return null;
        }
        return RecoveryPlan.draft("Accept the new dates",
                "move " + moves.size() + (moves.size() == 1 ? " date" : " dates") + " to fit the slip; nobody takes on extra work",
                moves, "rules");
    }

    /** Half the delay won back, rounded so at least one day is saved; null when the slip is too small to help. */
    static String recovered(Situation s) {
        if (s.oldDue() == null || s.newDue() == null) {
            return null;
        }
        LocalDate old = LocalDate.parse(s.oldDue());
        long delay = ChronoUnit.DAYS.between(old, LocalDate.parse(s.newDue()));
        if (delay < 2) {
            return null;
        }
        LocalDate target = old.plusDays(delay - delay / 2);
        LocalDate today = LocalDate.parse(s.today());
        return target.isBefore(today) ? null : target.toString();
    }

    static RecoveryPlan withHelper(Situation s, String helper) {
        String target = recovered(s);
        if (target == null) {
            return null;
        }
        Promise src = s.source();
        List<Move> moves = new ArrayList<>();
        moves.add(Move.addHelper(src, helper, "a second person on the late work"));
        moves.add(Move.moveDate(src, target, "assumes the extra help wins back about half the delay"));
        for (Ripple.Impact i : impactsFor(s, target)) {
            Promise p = s.promises().get(i.promiseId());
            if (p != null && later(i.likelyDue(), p.due())) {
                moves.add(Move.moveDate(p, i.likelyDue(), "moves less because the late work comes forward"));
            }
        }
        return RecoveryPlan.draft("Add " + helper + " to win back time",
                helper + " helps " + src.owner() + " so \"" + src.what() + "\" lands " + target + " instead of " + s.newDue()
                        + (moves.size() > 2 ? "; " + (moves.size() - 2) + " dependent date" + (moves.size() == 3 ? "" : "s") + " still move" : "; no other date moves"),
                moves, "rules");
    }

    static RecoveryPlan protectCustomers(Situation s, String helper) {
        String sourceDue = s.newDue();
        List<Move> moves = new ArrayList<>();
        if (helper != null) {
            sourceDue = recovered(s);
            if (sourceDue == null) {
                return null;
            }
            moves.add(Move.addHelper(s.source(), helper, "a second person on the late work"));
            moves.add(Move.moveDate(s.source(), sourceDue, "assumes the extra help wins back about half the delay"));
        }
        int protectedCount = 0;
        for (Ripple.Impact i : impactsFor(s, sourceDue)) {
            Promise p = s.promises().get(i.promiseId());
            if (p == null || !later(i.likelyDue(), p.due())) {
                continue;
            }
            if (s.customerFacing(p)) {
                protectedCount++;
                moves.add(Move.deliverPartial(p, "deliver what doesn't need the late work by " + p.due() + "; the rest follows"));
            } else {
                moves.add(Move.moveDate(p, i.likelyDue(), "keeps the same time after the work it waits on"));
            }
        }
        if (protectedCount == 0) {
            return null;
        }
        String title = helper == null ? "Protect the customer dates" : "Protect the customer dates, with help from " + helper;
        return RecoveryPlan.draft(title, protectedCount + " customer promise" + (protectedCount == 1 ? " keeps its date" : "s keep their dates")
                + " by delivering a first part" + (helper == null ? "" : ", and " + helper + " helps bring the late work forward"),
                moves, "rules");
    }
}
