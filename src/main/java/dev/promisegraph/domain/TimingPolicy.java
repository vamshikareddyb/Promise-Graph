package dev.promisegraph.domain;

import java.util.List;

/** How the promise workflow times reminders and checks. Hours are UTC until per-person time zones arrive. */
public record TimingPolicy(
        List<Integer> reminderOffsetsHours,
        int reminderHourUtc,
        int dueCheckHourUtc,
        int dueGraceHours,
        int autoActivateHours,
        int confirmTimeoutHours,
        int missingDateNudgeHours) {

    public TimingPolicy {
        reminderOffsetsHours = List.copyOf(reminderOffsetsHours);
    }

    /** Reminders 3 days and 1 day ahead at 09:30 India time; due check at 17:30; 2 days' grace. */
    public static TimingPolicy defaults() {
        return new TimingPolicy(List.of(72, 24), 4, 12, 48, 24, 72, 72);
    }
}
