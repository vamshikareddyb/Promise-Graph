package dev.promisegraph.domain;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToDoubleBiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides what a commitment means for existing promises:
 * new starts a promise (or updates one it restates), slip moves a date, done marks it kept.
 */
public final class Matching {

    /** Jaccard similarity at which a new commitment restates an existing promise. */
    public static final double NEW_DUPLICATE_THRESHOLD = 0.6;
    /** Overlap at which a slip or completion refers to an existing promise. */
    public static final double FOLLOW_UP_THRESHOLD = 0.5;

    private static final Set<String> STOPWORDS = Set.of((
            "a an the to for of and or with on in at by from this that these those it its my our your "
                    + "their them we i you he she they is are was were be been will can done me us so as all "
                    + "work plan deadline").split(" "));
    private static final Map<String, String> IRREGULAR = Map.of(
            "sent", "send", "written", "write", "wrote", "write", "made", "make", "ran", "run",
            "built", "build", "shipped", "ship", "did", "do", "gave", "give");
    private static final String[] SUFFIXES = {"ing", "ed", "es", "s", "e"};
    private static final Pattern WORD = Pattern.compile("[a-z0-9]+");

    private Matching() {}

    public enum Action { START, UPDATE, SLIP, KEPT, IGNORE }

    /**
     * @param promise the new promise, for START when it was created now
     * @param due the resolved new due date, for SLIP
     */
    public record Resolution(Action action, String promiseId, Commitment commitment, Promise promise, String due, String reason) {}

    static String stem(String word) {
        String w = IRREGULAR.getOrDefault(word, word);
        for (String suffix : SUFFIXES) {
            if (w.length() > suffix.length() + 2 && w.endsWith(suffix)) {
                return w.substring(0, w.length() - suffix.length());
            }
        }
        return w;
    }

    public static Set<String> tokens(String text) {
        Set<String> out = new HashSet<>();
        Matcher m = WORD.matcher(Ids.normalize(text));
        while (m.find()) {
            String w = m.group();
            if (!STOPWORDS.contains(w)) {
                out.add(stem(w));
            }
        }
        return out;
    }

    public static double jaccard(String a, String b) {
        Set<String> ta = tokens(a);
        Set<String> tb = tokens(b);
        if (ta.isEmpty() || tb.isEmpty()) {
            return 0;
        }
        Set<String> inter = new HashSet<>(ta);
        inter.retainAll(tb);
        Set<String> union = new HashSet<>(ta);
        union.addAll(tb);
        return (double) inter.size() / union.size();
    }

    public static double overlap(String a, String b) {
        Set<String> ta = tokens(a);
        Set<String> tb = tokens(b);
        if (ta.isEmpty() || tb.isEmpty()) {
            return 0;
        }
        Set<String> inter = new HashSet<>(ta);
        inter.retainAll(tb);
        return (double) inter.size() / Math.min(ta.size(), tb.size());
    }

    private record Best(Promise promise, double score) {}

    private static Best best(Commitment c, List<Promise> open, ToDoubleBiFunction<String, String> score) {
        Promise best = null;
        double bestScore = 0;
        for (Promise p : open) {
            if (!p.owner().equals(c.owner()) || !p.stillOpen()) {
                continue;
            }
            double s = score.applyAsDouble(c.what(), p.what());
            if (s > bestScore) {
                best = p;
                bestScore = s;
            }
        }
        return new Best(best, bestScore);
    }

    public static Resolution resolve(
            Commitment c, List<Promise> openPromises, String tenant, String nowIso, Set<String> existingIds) {
        if (c.kind() == CommitmentKind.NEW) {
            String pid = Ids.promiseId(tenant, c.messageId(), c.owner(), c.what());
            if (existingIds.contains(pid)) {
                // Created by an earlier attempt of this same step: starting again is safe.
                return new Resolution(Action.START, pid, c, null, null, "already created");
            }
            Best match = best(c, openPromises, Matching::jaccard);
            if (match.promise() != null && match.score() >= NEW_DUPLICATE_THRESHOLD) {
                return new Resolution(Action.UPDATE, match.promise().id(), c, null, null,
                        "restates (%.2f)".formatted(match.score()));
            }
            Promise promise = new Promise(pid, tenant, c.owner(), c.what(), PromiseStatus.DETECTED,
                    c.confidence(), nowIso, nowIso, c.recipient(), c.due(), c.channel(),
                    List.of(new Evidence(c.messageId(), c.quote(), c.channel())), 0);
            return new Resolution(Action.START, pid, c, promise, null, "new");
        }

        Best match = best(c, openPromises, Matching::overlap);
        if (match.promise() == null || match.score() < FOLLOW_UP_THRESHOLD) {
            return new Resolution(Action.IGNORE, null, c, null, null, "no matching open promise");
        }
        String reason = "matches (%.2f)".formatted(match.score());
        if (c.kind() == CommitmentKind.DONE) {
            return new Resolution(Action.KEPT, match.promise().id(), c, null, null, reason);
        }
        String newDue = c.due();
        if (newDue == null && c.shiftDays() != null && match.promise().due() != null) {
            newDue = LocalDate.parse(match.promise().due()).plusDays(c.shiftDays()).toString();
        }
        return new Resolution(Action.SLIP, match.promise().id(), c, null, newDue, reason);
    }
}
