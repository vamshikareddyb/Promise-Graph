package dev.promisegraph.extraction;

import dev.promisegraph.domain.Commitment;
import dev.promisegraph.domain.CommitmentKind;
import dev.promisegraph.domain.Message;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Requests made to the reader of an email: "You need to submit the project report by Friday",
 * "Please send the invoice by Monday", "Can you review this by tomorrow?". In a personal mailbox
 * these are things the mailbox owner now owes, so they become the owner's promises, waiting for
 * the owner to confirm them.
 *
 * <p>Only emails are read this way (chat messages go to many people, so "you" is ambiguous), and
 * only requests with a deadline count, which keeps "please find attached" and "can you believe
 * it" out. The extractor marks the owner as {@link #ADDRESSEE}; the step that saves promises
 * replaces it with the connected mailbox, or drops the request if there is none.
 */
public final class Requests {

    /** Stands for "whoever this email was sent to" until the mailbox owner is known. */
    public static final String ADDRESSEE = "@addressee";

    /** Below the default auto-confirm threshold (0.8): the owner confirms a request they were given. */
    static final double CONFIDENCE = 0.7;

    private static final int CI = Pattern.CASE_INSENSITIVE;
    private static final Pattern REQUEST = Pattern.compile(
            "\\b(?:you need to|you have to|you must|you should|you'll need to|you will need to|you are to|"
                    + "i need you to|i'd like you to|make sure (?:you|to)|please|kindly|"
                    + "can you|could you|would you|will you)\\s+(?:please\\s+)?(.+)$", CI);
    /** Politeness that asks for nothing to be done. */
    private static final Pattern NOT_A_TASK = Pattern.compile(
            "^(?:find|see|note|ignore|disregard|feel free|don't hesitate|do not hesitate|accept|excuse|forgive)\\b", CI);
    private static final Pattern DEADLINE_TAIL = Pattern.compile(
            "\\s+(?:by|before|on|until|till|no later than|this|next|tomorrow|today|eod|asap)\\b.*$", CI);
    private static final Pattern CLAUSE_TAIL = Pattern.compile("\\s+(?:so|once|when|because|after|if)\\b.*$", CI);

    private Requests() {}

    /** Emails only: their channel is {@code email:<thread>}. */
    public static boolean applies(Message m) {
        return m.channel() != null && m.channel().startsWith("email:");
    }

    /** The first request with a deadline in the message, as a commitment owed by {@link #ADDRESSEE}. */
    public static Optional<Commitment> find(Message m) {
        if (!applies(m)) {
            return Optional.empty();
        }
        LocalDate sent = m.timestamp().toLocalDate();
        for (String sentence : Detect.sentences(m.text())) {
            String s = sentence.replace('’', '\'');
            Matcher r = REQUEST.matcher(s);
            if (!r.find()) {
                continue;
            }
            String task = r.group(1).strip();
            if (NOT_A_TASK.matcher(task).find()) {
                continue;
            }
            Optional<LocalDate> due = DueDates.parseDue(s, sent);
            if (due.isEmpty()) {
                continue;
            }
            String what = clean(CLAUSE_TAIL.matcher(DEADLINE_TAIL.matcher(task).replaceFirst("")).replaceFirst(""));
            if (what.length() < 3) {
                continue;
            }
            return Optional.of(new Commitment(CommitmentKind.NEW, m.id(), ADDRESSEE, what, sentence.strip(), CONFIDENCE,
                    m.channel(), m.sender(), due.get().toString(), null));
        }
        return Optional.empty();
    }

    private static String clean(String text) {
        String t = text.strip().replaceAll("[.!?,;:]+$", "").strip().replaceAll("\\s+", " ");
        return t.isEmpty() ? t : t.substring(0, 1).toLowerCase(Locale.ROOT) + t.substring(1);
    }
}
