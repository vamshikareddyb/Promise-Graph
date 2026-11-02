package dev.promisegraph.domain;

/**
 * One commitment found in one message, before it is matched to a promise.
 *
 * @param due ISO date, or null
 * @param shiftDays for slips stated as a length ("by two weeks"); null otherwise
 */
public record Commitment(
        CommitmentKind kind,
        String messageId,
        String owner,
        String what,
        String quote,
        double confidence,
        String channel,
        String recipient,
        String due,
        Integer shiftDays) {}
