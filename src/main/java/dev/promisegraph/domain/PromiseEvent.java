package dev.promisegraph.domain;

import java.util.Map;

/** One entry in a promise's append-only history. {@code idemKey} makes each write happen once. */
public record PromiseEvent(
        String tenant, String promiseId, String kind, String actor, String at, String idemKey, Map<String, String> detail) {

    public PromiseEvent {
        detail = detail == null ? Map.of() : Map.copyOf(detail);
    }
}
