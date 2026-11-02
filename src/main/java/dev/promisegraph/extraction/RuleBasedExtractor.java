package dev.promisegraph.extraction;

import dev.promisegraph.domain.Commitment;
import dev.promisegraph.domain.CommitmentKind;
import dev.promisegraph.domain.Message;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic, offline extractor. Used in development, tests and as the evaluation baseline.
 * Produces at most one commitment per message: the strongest candidate sentence.
 */
public final class RuleBasedExtractor implements Extractor {

    private static final int CI = Pattern.CASE_INSENSITIVE;
    private static final Pattern LEADING = Pattern.compile("^(?:(?:sure|ok|okay|on it|yes|heads up)[,.:!]?\\s*)*", CI);
    private static final Pattern NEW_LEAD = Pattern.compile(
            "^.*?\\b(?:i'll|i will|we'll|we will|i can|we can|i'm going to|i am going to|"
                    + "we're going to|we are going to)\\s+", CI);
    private static final Pattern DEADLINE_TAIL = Pattern.compile(
            "\\s+(?:by|before|on|until|till|this|next|tomorrow|today|eod)\\b.*$", CI);
    private static final Pattern CLAUSE_TAIL = Pattern.compile("\\s+(?:so|once|when|because|after)\\b.*$", CI);
    private static final List<Pattern> SLIP_SUBJECTS = List.of(
            Pattern.compile("push the (?:deadline|date) (?:to|for) (.+?) to\\b", CI),
            Pattern.compile("^(?:my plan to )?(.+?)\\s+(?:will slip|slips?|slipped|is delayed|are delayed|will be delayed)\\b", CI));
    private static final List<Pattern> DONE_SUBJECTS = List.of(
            Pattern.compile("^done\\s*[:,-]\\s*(.+)$", CI),
            Pattern.compile("^i've\\s+(.+)$", CI),
            Pattern.compile("^(.+?)\\s+(?:is|are|has been|have been)\\s+(?:done|finished|finalised|finalized|shipped|live|merged|sent)\\b", CI));
    private static final Pattern FIRST_PERSON_WILL = Pattern.compile("\\b(i'll|i will)\\b", CI);

    @Override
    public List<Commitment> extract(List<Message> messages, Set<String> directory) {
        List<Commitment> results = new ArrayList<>();
        for (Message message : messages) {
            Detect.candidates(message.text()).stream()
                    .max(Comparator.comparingDouble(Detect.Candidate::score))
                    .flatMap(best -> commitmentFrom(best, message))
                    .ifPresent(results::add);
        }
        return results;
    }

    private static String clean(String text) {
        String t = text.strip();
        while (t.endsWith(".") || t.endsWith("!")) {
            t = t.substring(0, t.length() - 1);
        }
        return t.strip().replaceAll("\\s+", " ");
    }

    static String whatForNew(String sentence) {
        String body = NEW_LEAD.matcher(LEADING.matcher(sentence).replaceFirst("")).replaceFirst("");
        body = body.split(",", 2)[0];
        body = DEADLINE_TAIL.matcher(body).replaceFirst("");
        body = CLAUSE_TAIL.matcher(body).replaceFirst("");
        return clean(body);
    }

    static String whatForSlip(String sentence) {
        String text = LEADING.matcher(sentence).replaceFirst("");
        for (Pattern p : SLIP_SUBJECTS) {
            Matcher m = p.matcher(text);
            if (m.find()) {
                return clean(m.group(1));
            }
        }
        return clean(text);
    }

    static String whatForDone(String sentence) {
        String text = LEADING.matcher(sentence).replaceFirst("").strip();
        for (Pattern p : DONE_SUBJECTS) {
            Matcher m = p.matcher(text);
            if (m.find()) {
                return clean(m.group(1));
            }
        }
        return clean(text);
    }

    private static Optional<Commitment> commitmentFrom(Detect.Candidate candidate, Message message) {
        LocalDate sent = message.timestamp().toLocalDate();
        String sentence = candidate.sentence();
        return switch (candidate.kind()) {
            case NEW -> {
                Optional<LocalDate> due = DueDates.parseDue(sentence, sent);
                String what = whatForNew(sentence);
                double confidence = 0.55 + (due.isPresent() ? 0.3 : 0.05)
                        + (FIRST_PERSON_WILL.matcher(sentence).find() ? 0.05 : 0);
                confidence = Math.round(Math.min(confidence, 0.95) * 100) / 100.0;
                yield what.length() < 3 ? Optional.empty() : Optional.of(new Commitment(
                        CommitmentKind.NEW, message.id(), message.sender(), what, sentence, confidence,
                        message.channel(), null, due.map(LocalDate::toString).orElse(null), null));
            }
            case SLIP -> {
                Optional<LocalDate> due = DueDates.parseDue(sentence, sent);
                Integer shift = due.isPresent() ? null : DueDates.parseShiftDays(sentence).orElse(null);
                yield Optional.of(new Commitment(
                        CommitmentKind.SLIP, message.id(), message.sender(), whatForSlip(sentence), sentence,
                        due.isPresent() || shift != null ? 0.85 : 0.65, message.channel(), null,
                        due.map(LocalDate::toString).orElse(null), shift));
            }
            case DONE -> Optional.of(new Commitment(
                    CommitmentKind.DONE, message.id(), message.sender(), whatForDone(sentence), sentence, 0.85,
                    message.channel(), null, null, null));
        };
    }
}
