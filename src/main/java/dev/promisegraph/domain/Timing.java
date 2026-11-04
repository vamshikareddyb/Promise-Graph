package dev.promisegraph.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** When a promise needs attention. Every method takes {@code now} explicitly so workflows stay deterministic. */
public final class Timing {

    private Timing() {}

    public static List<Instant> reminderTimes(LocalDate due, TimingPolicy policy) {
        Instant base = due.atTime(LocalTime.of(policy.reminderHourUtc(), 0)).toInstant(ZoneOffset.UTC);
        return policy.reminderOffsetsHours().stream()
                .map(h -> base.minus(h, ChronoUnit.HOURS))
                .sorted()
                .toList();
    }

    public static Instant dueCheckTime(LocalDate due, TimingPolicy policy) {
        return due.atTime(LocalTime.of(policy.dueCheckHourUtc(), 0)).toInstant(ZoneOffset.UTC);
    }

    public static Instant brokenDeadline(LocalDate due, TimingPolicy policy) {
        return dueCheckTime(due, policy).plus(policy.dueGraceHours(), ChronoUnit.HOURS);
    }

    /** The earliest reminder still in the future and not yet sent. */
    public static Optional<Instant> nextReminder(LocalDate due, Instant now, Set<String> alreadySent, TimingPolicy policy) {
        return reminderTimes(due, policy).stream()
                .filter(at -> at.isAfter(now) && !alreadySent.contains(at.toString()))
                .findFirst();
    }
}
