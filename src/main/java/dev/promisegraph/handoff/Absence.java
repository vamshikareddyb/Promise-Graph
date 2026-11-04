package dev.promisegraph.handoff;

import java.time.LocalDate;

/**
 * A planned absence. Dates are ISO strings so the record crosses Temporal boundaries as is.
 *
 * @param endsOn last day away, or null for a permanent exit
 * @param source the message it was found in, or "manual"
 */
public record Absence(String tenant, String person, String startsOn, String endsOn, String reason, String source) {

    public Absence {
        LocalDate start = LocalDate.parse(startsOn);
        if (endsOn != null && LocalDate.parse(endsOn).isBefore(start)) {
            throw new IllegalArgumentException("an absence cannot end before it starts");
        }
    }

    public boolean overlaps(String from, String to) {
        LocalDate a = LocalDate.parse(startsOn);
        LocalDate b = endsOn == null ? LocalDate.MAX : LocalDate.parse(endsOn);
        LocalDate c = LocalDate.parse(from);
        LocalDate d = to == null ? LocalDate.MAX : LocalDate.parse(to);
        return !a.isAfter(d) && !c.isAfter(b);
    }

    /** One handoff per person and start date. */
    public String handoffId() {
        return tenant + "-" + person.replaceAll("[^A-Za-z0-9._-]", "_") + "-" + startsOn;
    }
}
