package dev.promisegraph.domain;

import java.util.Locale;

public enum PromiseStatus {
    DETECTED,
    ACTIVE,
    AT_RISK,
    KEPT,
    BROKEN,
    DISMISSED,
    CANCELLED;

    public boolean isOpen() {
        return this == DETECTED || this == ACTIVE || this == AT_RISK;
    }

    /** Lower-case form used in the database. */
    public String dbValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static PromiseStatus fromDb(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
