package dev.promisegraph.domain;

/** The message that shows a promise was made or changed, with the exact words quoted. */
public record Evidence(String messageId, String quote, String channel) {}
