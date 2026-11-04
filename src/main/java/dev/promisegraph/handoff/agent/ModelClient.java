package dev.promisegraph.handoff.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Sends one Messages API request and returns the response. Implemented over HTTP; faked in tests. */
public interface ModelClient {

    JsonNode send(ObjectNode request);
}
