package dev.promisegraph.domain;

public enum CommitmentKind {
    /** A fresh commitment. */
    NEW,
    /** An existing commitment moves later. */
    SLIP,
    /** An existing commitment was delivered. */
    DONE
}
