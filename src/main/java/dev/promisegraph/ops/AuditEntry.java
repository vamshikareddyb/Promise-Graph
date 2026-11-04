package dev.promisegraph.ops;

/**
 * One action by a person or by the system.
 *
 * @param action e.g. "inbox:approve B", "link:confirm", "absence:add", "reconcile:restart"
 * @param target what it acted on: a promise, link, handoff, negotiation or dead letter
 */
public record AuditEntry(String tenant, String at, String actor, String action, String target, String detail) {}
