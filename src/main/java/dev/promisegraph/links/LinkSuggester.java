package dev.promisegraph.links;

import dev.promisegraph.domain.Matching;
import dev.promisegraph.domain.Promise;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Suggests what a new promise depends on, from dependency phrases in the sentence that made it:
 * "I'll build the ingestion job by 18 Nov, once the API is live" depends on a promise about the API.
 * Suggestions are only proposals; a person confirms or rejects each one.
 */
public final class LinkSuggester {

    /** A suggested dependency: {@code upstreamId} must be kept before the new promise can be. */
    public record Suggestion(String upstreamId, String clause, double score) {}

    static final double MIN_OVERLAP = 0.5;

    private static final Pattern DEPENDENCY = Pattern.compile(
            "\\b(once|after|as soon as|when|until|depends on|depending on|blocked (?:on|by)|waiting (?:on|for))\\s+(.+?)(?:[.,;!?]|$)");

    /** Words that describe a state ("is live") rather than the thing waited for. */
    private static final Set<String> STATE_WORDS = Matching.tokens(
            "live ready done merged shipped out available finished complete completed decided finalised finalized "
                    + "approved signed made in place there back up running deployed released");

    private LinkSuggester() {}

    /** The text after a dependency phrase, or empty if the sentence has none. */
    static Optional<String> dependencyClause(String sentence) {
        Matcher m = DEPENDENCY.matcher(sentence.toLowerCase(Locale.ROOT).replace('’', '\''));
        return m.find() ? Optional.of(m.group(2).strip()) : Optional.empty();
    }

    /** Cheap check before loading every open promise to match against. */
    public static boolean hasDependencyPhrase(String sentence) {
        return dependencyClause(sentence).isPresent();
    }

    static Set<String> clauseTokens(String clause) {
        Set<String> t = new HashSet<>(Matching.tokens(clause));
        t.removeAll(STATE_WORDS);
        return t;
    }

    /**
     * @param quote the sentence the new promise was found in
     * @param candidates open promises it could depend on
     */
    public static Optional<Suggestion> suggest(Promise newPromise, String quote, List<Promise> candidates) {
        Optional<String> clause = dependencyClause(quote);
        if (clause.isEmpty()) {
            return Optional.empty();
        }
        Set<String> wanted = clauseTokens(clause.get());
        if (wanted.isEmpty()) {
            return Optional.empty();
        }
        Promise best = null;
        double bestOverlap = 0;
        double bestJaccard = 0;
        for (Promise p : candidates) {
            if (p.id().equals(newPromise.id()) || !p.stillOpen()) {
                continue;
            }
            Set<String> have = Matching.tokens(p.what());
            if (have.isEmpty()) {
                continue;
            }
            Set<String> inter = new HashSet<>(wanted);
            inter.retainAll(have);
            if (inter.isEmpty()) {
                continue;
            }
            Set<String> union = new HashSet<>(wanted);
            union.addAll(have);
            double overlap = (double) inter.size() / Math.min(wanted.size(), have.size());
            double jaccard = (double) inter.size() / union.size();
            boolean better = overlap > bestOverlap
                    || (overlap == bestOverlap && jaccard > bestJaccard)
                    || (overlap == bestOverlap && jaccard == bestJaccard && best != null && p.id().compareTo(best.id()) < 0);
            if (better) {
                best = p;
                bestOverlap = overlap;
                bestJaccard = jaccard;
            }
        }
        if (best == null || bestOverlap < MIN_OVERLAP) {
            return Optional.empty();
        }
        return Optional.of(new Suggestion(best.id(), clause.get(), bestOverlap));
    }
}
