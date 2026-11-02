package dev.promisegraph.extraction;

import dev.promisegraph.domain.CommitmentKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The cheap commitment filter. Splits a message into sentences and flags those that look
 * like a first-person commitment, a slip or a completion. It runs before any model call,
 * so most chatter never reaches the expensive extraction step.
 */
public final class Detect {

    /** A sentence that looks like a commitment. {@code sentence} is an exact substring of the message. */
    public record Candidate(String sentence, CommitmentKind kind, double score) {}

    private static final Pattern SENTENCE = Pattern.compile("[^.!?]+[.!?]?");

    private static final Pattern NEW = Pattern.compile(
            "\\b(i'll|i will|we'll|we will|i can|we can|i'm going to|i am going to|"
                    + "we're going to|we are going to)\\s+(?!be\\b|need\\b|try\\b|see\\b|think\\b|check if\\b)");
    private static final Pattern SLIP = Pattern.compile(
            "\\b(will slip|slips? (?:to|by)|slipped (?:to|by)|(?:is|are|will be) delayed|"
                    + "need to push|have to push|pushing (?:it|this) to|moving (?:it|this) to)\\b");
    /** Strong completion markers win over a commitment phrase in the same sentence. */
    private static final Pattern DONE_STRONG = Pattern.compile(
            "(^\\s*done\\b\\s*[:,-]|\\bi've (?!been\\b|got\\b)\\w+ed\\b|"
                    + "\\bi've (?:sent|written|run|made|built|set up|shipped)\\b)");
    /** Weak markers ("is live") count only without a commitment phrase: "I'll build it once the API is live". */
    private static final Pattern DONE_WEAK = Pattern.compile(
            "\\b(?:is|are|has been|have been) (?:done|finished|finalised|finalized|shipped|live|merged|sent)\\b");
    private static final Pattern HEDGE = Pattern.compile(
            "\\b(maybe|might|perhaps|not sure|could we|should we|can someone|can you|could you|"
                    + "would you|who owns|did anyone|if i get time|let's discuss)\\b");
    /** "I'm on leave", "I'll be out" are availability, not commitments. */
    private static final Pattern AVAILABILITY = Pattern.compile("\\b(on leave|out of office|ooo|be out|be away|on holiday)\\b");

    private Detect() {}

    public static List<String> sentences(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = SENTENCE.matcher(text);
        while (m.find()) {
            String s = m.group().strip();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    public static Candidate classify(String sentence) {
        String lowered = sentence.toLowerCase(Locale.ROOT).replace('’', '\'');
        if (sentence.stripTrailing().endsWith("?") || HEDGE.matcher(lowered).find()) {
            return null;
        }
        if (AVAILABILITY.matcher(lowered).find()) {
            return null;
        }
        if (SLIP.matcher(lowered).find()) {
            return new Candidate(sentence, CommitmentKind.SLIP, 0.8);
        }
        if (DONE_STRONG.matcher(lowered).find()) {
            return new Candidate(sentence, CommitmentKind.DONE, 0.8);
        }
        if (NEW.matcher(lowered).find()) {
            return new Candidate(sentence, CommitmentKind.NEW, 0.6);
        }
        if (DONE_WEAK.matcher(lowered).find()) {
            return new Candidate(sentence, CommitmentKind.DONE, 0.7);
        }
        return null;
    }

    public static List<Candidate> candidates(String text) {
        return sentences(text).stream().map(Detect::classify).filter(c -> c != null).toList();
    }

    public static boolean looksLikeCommitment(String text) {
        return !candidates(text).isEmpty();
    }
}
