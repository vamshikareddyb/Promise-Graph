package dev.promisegraph.handoff;

import dev.promisegraph.domain.Message;
import dev.promisegraph.extraction.Detect;
import dev.promisegraph.extraction.DueDates;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the sender's own planned absences in a message: "I'm on leave from 16 Nov to 27 Nov",
 * "I'll be out until Friday", "OOO tomorrow", and departures: "My last day is 20 Nov",
 * "I'm leaving the company on Friday". Questions and other people's absences are ignored, and a
 * departure needs a date.
 */
public final class AbsenceDetector {

    private static final Pattern AWAY = Pattern.compile(
            "\\b(on leave|out of (the )?office|ooo|on vacation|on holiday|on pto|be out|be away|be off|taking (a )?(day|days|week|leave) off|off work)\\b");
    private static final Pattern FIRST_PERSON = Pattern.compile("\\b(i'm|i am|i'll|i will|i'll be|i won't|i'm going to|my)\\b|^\\s*ooo\\b");
    private static final Pattern FROM_TO = Pattern.compile(
            "\\bfrom (.+?)\\s+(?:to|until|till|through|thru|-|–)\\s+(.+?)(?:[.,;!?]|$)");
    private static final Pattern UNTIL = Pattern.compile("\\b(?:until|till|through|thru)\\s+(.+?)(?:[.,;!?]|$)");
    private static final Pattern NEXT_WEEK = Pattern.compile("\\bnext week\\b");
    /** Leaving for good: "my last day is 20 Nov", "I'm leaving the company on Friday", "I've resigned". */
    private static final Pattern DEPARTURE = Pattern.compile(
            "\\b(my last (?:working )?day|last day (?:at|with|here|in the office)|"
                    + "(?:i'm|i am|i'll be|i will be) leaving (?:the|this|my) (?:company|team|firm|organi[sz]ation|role|job)|"
                    + "i've resigned|i have resigned|i'm resigning|i am resigning|"
                    + "handed in my (?:notice|resignation)|serving (?:my )?notice|my notice period ends)\\b");
    /** "Leaving early today" or "leaving for lunch" isn't leaving the company. */
    private static final Pattern NOT_DEPARTURE = Pattern.compile("\\bleaving (?:early|for|now|soon for|the office|at \\d)");

    private AbsenceDetector() {}

    public static Optional<Absence> detect(Message m) {
        LocalDate sent = m.timestamp().toLocalDate();
        for (String sentence : Detect.sentences(m.text())) {
            String s = sentence.toLowerCase(Locale.ROOT).replace('’', '\'');
            if (sentence.stripTrailing().endsWith("?")) {
                continue;
            }
            Optional<LocalDate> lastDay = lastDay(s, sent);
            if (lastDay.isPresent()) {
                // A permanent exit: from the day after the last day, with no end.
                return Optional.of(new Absence(m.tenant(), m.sender(), lastDay.get().plusDays(1).toString(), null, DEPARTURE_REASON, m.id()));
            }
            if (!AWAY.matcher(s).find() || !FIRST_PERSON.matcher(s).find()) {
                continue;
            }
            Optional<LocalDate[]> range = range(s, sent);
            if (range.isPresent()) {
                LocalDate[] r = range.get();
                return Optional.of(new Absence(m.tenant(), m.sender(), r[0].toString(), r[1].toString(), "leave", m.id()));
            }
        }
        return Optional.empty();
    }

    /** The reason recorded for someone leaving the company. */
    public static final String DEPARTURE_REASON = "departure";

    /** The sender's own last day, when the sentence says they are leaving the company and names a date. */
    static Optional<LocalDate> lastDay(String s, LocalDate sent) {
        if (!DEPARTURE.matcher(s).find() || NOT_DEPARTURE.matcher(s).find()) {
            return Optional.empty();
        }
        return DueDates.parseDue(s, sent).filter(d -> !d.isBefore(sent));
    }

    static Optional<LocalDate[]> range(String s, LocalDate sent) {
        Matcher fromTo = FROM_TO.matcher(s);
        if (fromTo.find()) {
            Optional<LocalDate> a = DueDates.parseDue(fromTo.group(1), sent);
            Optional<LocalDate> b = DueDates.parseDue(fromTo.group(2), sent);
            if (a.isPresent() && b.isPresent() && !b.get().isBefore(a.get())) {
                return Optional.of(new LocalDate[] {a.get(), b.get()});
            }
        }
        if (NEXT_WEEK.matcher(s).find()) {
            LocalDate monday = sent.with(TemporalAdjusters.next(DayOfWeek.MONDAY));
            return Optional.of(new LocalDate[] {monday, monday.plusDays(4)});
        }
        Matcher until = UNTIL.matcher(s);
        if (until.find()) {
            Optional<LocalDate> end = DueDates.parseDue(until.group(1), sent);
            if (end.isPresent() && !end.get().isBefore(sent)) {
                return Optional.of(new LocalDate[] {sent, end.get()});
            }
        }
        // A single day: "OOO tomorrow", "on leave on Friday".
        return DueDates.parseDue(s, sent).filter(d -> !d.isBefore(sent)).map(d -> new LocalDate[] {d, d});
    }
}
