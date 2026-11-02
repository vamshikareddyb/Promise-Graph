package dev.promisegraph.extraction;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves due-date phrases ("by Friday", "13 Nov", "next week") relative to when a message was sent. */
public final class DueDates {

    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("jan", 1), Map.entry("feb", 2), Map.entry("mar", 3), Map.entry("apr", 4),
            Map.entry("may", 5), Map.entry("jun", 6), Map.entry("jul", 7), Map.entry("aug", 8),
            Map.entry("sep", 9), Map.entry("oct", 10), Map.entry("nov", 11), Map.entry("dec", 12));
    private static final Map<String, Integer> NUMBER_WORDS = Map.ofEntries(
            Map.entry("a", 1), Map.entry("one", 1), Map.entry("two", 2), Map.entry("three", 3),
            Map.entry("four", 4), Map.entry("five", 5), Map.entry("six", 6), Map.entry("seven", 7),
            Map.entry("eight", 8), Map.entry("nine", 9), Map.entry("ten", 10));
    private static final String MONTH_RE = "(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\.?";
    private static final String WEEKDAY_RE = "(monday|tuesday|wednesday|thursday|friday|saturday|sunday)";
    private static final String NUMBER_RE = "(\\d+|a|one|two|three|four|five|six|seven|eight|nine|ten)";
    private static final Pattern TRIGGER = Pattern.compile("\\b(by|before|on|to|until|till|for)\\s*$");

    private enum Kind { ISO, DAY_MONTH, MONTH_DAY, TOMORROW, TODAY, END_OF_WEEK, NEXT_WEEK, END_OF_MONTH, NEXT_WEEKDAY, WEEKDAY, IN_N }

    private record Rule(Kind kind, Pattern pattern) {}

    // Order matters: more specific patterns first; later ones skip spans already taken.
    private static final List<Rule> RULES = List.of(
            new Rule(Kind.ISO, Pattern.compile("\\b(\\d{4})-(\\d{2})-(\\d{2})\\b")),
            new Rule(Kind.DAY_MONTH, Pattern.compile("\\b(\\d{1,2})(?:st|nd|rd|th)?\\s+" + MONTH_RE + "(?![a-z])")),
            new Rule(Kind.MONTH_DAY, Pattern.compile("\\b" + MONTH_RE + "\\s+(\\d{1,2})(?:st|nd|rd|th)?\\b")),
            new Rule(Kind.TOMORROW, Pattern.compile("\\btomorrow\\b")),
            new Rule(Kind.TODAY, Pattern.compile("\\b(eod|end of (the )?day|today|tonight)\\b")),
            new Rule(Kind.END_OF_WEEK, Pattern.compile("\\bend of (the )?week\\b")),
            new Rule(Kind.NEXT_WEEK, Pattern.compile("\\bnext week\\b")),
            new Rule(Kind.END_OF_MONTH, Pattern.compile("\\bend of (the )?month\\b")),
            new Rule(Kind.NEXT_WEEKDAY, Pattern.compile("\\bnext " + WEEKDAY_RE + "\\b")),
            new Rule(Kind.WEEKDAY, Pattern.compile("\\b" + WEEKDAY_RE + "\\b")),
            new Rule(Kind.IN_N, Pattern.compile("\\bin " + NUMBER_RE + " (day|days|week|weeks)\\b")));

    private static final Pattern SHIFT = Pattern.compile(
            "\\b(?:slip|slips|slipped|delay|delayed|push|pushed|move|moved)\\b[^.]*?\\bby "
                    + NUMBER_RE + " (day|days|week|weeks)\\b");

    private DueDates() {}

    /** Next occurrence of {@code day}, counting {@code from} itself. */
    static LocalDate nextOrSame(LocalDate from, DayOfWeek day) {
        return from.with(TemporalAdjusters.nextOrSame(day));
    }

    private static int number(String word) {
        return word.chars().allMatch(Character::isDigit) ? Integer.parseInt(word) : NUMBER_WORDS.get(word);
    }

    private static LocalDate explicit(int day, int month, LocalDate sent) {
        try {
            LocalDate candidate = LocalDate.of(sent.getYear(), month, day);
            // A date well in the past most likely means next year ("by 5 Jan" said in December).
            return candidate.isBefore(sent.minusDays(60)) ? candidate.plusYears(1) : candidate;
        } catch (DateTimeException e) {
            return null;
        }
    }

    private static DayOfWeek weekday(String name) {
        return DayOfWeek.valueOf(name.toUpperCase(Locale.ROOT));
    }

    private static LocalDate resolve(Kind kind, Matcher m, LocalDate sent) {
        return switch (kind) {
            case ISO -> {
                try {
                    yield LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
                } catch (DateTimeException e) {
                    yield null;
                }
            }
            case DAY_MONTH -> explicit(Integer.parseInt(m.group(1)), MONTHS.get(m.group(2).substring(0, 3)), sent);
            case MONTH_DAY -> explicit(Integer.parseInt(m.group(2)), MONTHS.get(m.group(1).substring(0, 3)), sent);
            case TOMORROW -> sent.plusDays(1);
            case TODAY -> sent;
            case END_OF_WEEK -> nextOrSame(sent, DayOfWeek.FRIDAY);
            case NEXT_WEEK -> sent.plusDays(7 - (sent.getDayOfWeek().getValue() - 1) + 4L);
            case END_OF_MONTH -> sent.with(TemporalAdjusters.lastDayOfMonth());
            case NEXT_WEEKDAY -> nextOrSame(sent, weekday(m.group(1))).plusDays(7);
            case WEEKDAY -> nextOrSame(sent, weekday(m.group(1)));
            case IN_N -> sent.plusDays((long) number(m.group(1)) * (m.group(2).startsWith("week") ? 7 : 1));
        };
    }

    private record Found(boolean afterTrigger, int start, LocalDate date) {}

    /**
     * The due date a sentence states. When several dates appear, prefer one introduced by a
     * deadline word ("by", "before", "to", ...); otherwise take the first.
     */
    public static Optional<LocalDate> parseDue(String text, LocalDate sent) {
        String lowered = text.toLowerCase(Locale.ROOT);
        List<Found> found = new ArrayList<>();
        List<int[]> taken = new ArrayList<>();
        for (Rule rule : RULES) {
            Matcher m = rule.pattern().matcher(lowered);
            while (m.find()) {
                int s = m.start();
                int e = m.end();
                if (taken.stream().anyMatch(t -> t[0] < e && s < t[1])) {
                    continue;
                }
                LocalDate date = resolve(rule.kind(), m, sent);
                if (date == null) {
                    continue;
                }
                taken.add(new int[] {s, e});
                boolean afterTrigger = TRIGGER.matcher(lowered.substring(0, s)).find();
                found.add(new Found(afterTrigger, s, date));
            }
        }
        return found.stream()
                .min(Comparator.comparing((Found f) -> !f.afterTrigger()).thenComparingInt(Found::start))
                .map(Found::date);
    }

    /** Days of delay in phrases like "will slip by two weeks". */
    public static Optional<Integer> parseShiftDays(String text) {
        Matcher m = SHIFT.matcher(text.toLowerCase(Locale.ROOT));
        if (!m.find()) {
            return Optional.empty();
        }
        return Optional.of(number(m.group(1)) * (m.group(2).startsWith("week") ? 7 : 1));
    }
}
