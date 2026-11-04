package dev.promisegraph.handoff.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.promisegraph.domain.Promise;
import dev.promisegraph.domain.PromiseStatus;
import dev.promisegraph.handoff.Directory;
import dev.promisegraph.handoff.HandoffPlanning;
import dev.promisegraph.sim.Company;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The agent loop against a scripted model: no network, fully repeatable. */
class HandoffAgentTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Promise faq = new Promise("faq", "demo", "karan", "write the launch FAQ for support", PromiseStatus.ACTIVE, 0.9,
            "2026-11-09T06:00:00Z", "2026-11-09T06:00:00Z", null, "2026-11-19", "", List.of(), 1);
    private final HandoffPlanning.Context ctx = new HandoffPlanning.Context("h1", "karan", "2026-11-16", "2026-11-27",
            List.of(faq), Map.of(), Map.of(), Map.of(), Set.of(), new Directory(Company.PEOPLE));

    /** Replays canned responses in order and records every request it receives. */
    static final class ScriptedModel implements ModelClient {
        final List<JsonNode> script;
        final List<ObjectNode> requests = new ArrayList<>();

        ScriptedModel(String... responses) {
            script = new ArrayList<>();
            for (String r : responses) {
                try {
                    script.add(MAPPER.readTree(r));
                } catch (Exception e) {
                    throw new IllegalArgumentException(e);
                }
            }
        }

        @Override
        public JsonNode send(ObjectNode request) {
            requests.add(request.deepCopy());
            return script.remove(0);
        }
    }

    static String toolUse(String id, String name, String input) {
        return """
                {"stop_reason":"tool_use","content":[{"type":"text","text":"Looking."},
                 {"type":"tool_use","id":"%s","name":"%s","input":%s}]}""".formatted(id, name, input);
    }

    @Test
    void usesToolsCorrectsABadChoiceAndProposes() {
        ScriptedModel model = new ScriptedModel(
                toolUse("t1", "get_candidates", "{\"promise_id\":\"faq\"}"),
                toolUse("t2", "propose", "{\"promise_id\":\"faq\",\"owner\":\"mallory\",\"brief\":\"x\",\"reason\":\"x\"}"),
                toolUse("t3", "propose", "{\"promise_id\":\"faq\",\"owner\":\"ritu\",\"brief\":\"Karan promised support a launch FAQ by 19 Nov. Draft it from the release notes.\",\"reason\":\"has time this week\"}"));

        var plan = new HandoffAgent(model, "test-model", 8, new HandoffPlanning.RulePlanner()).plan(ctx);

        assertThat(plan.planner()).isEqualTo("agent");
        assertThat(plan.items()).singleElement().satisfies(i -> {
            assertThat(i.proposedOwner()).isEqualTo("ritu");
            assertThat(i.brief()).startsWith("Karan promised support");
            assertThat(i.reason()).isEqualTo("has time this week");
        });
        assertThat(model.requests).hasSize(3); // stopped once every promise was decided

        // The bad owner was refused and the refusal went back to the model as a tool result.
        JsonNode third = model.requests.get(2).path("messages");
        String lastToolResult = third.get(third.size() - 1).path("content").get(0).path("content").asText();
        assertThat(lastToolResult).startsWith("error: mallory is not an available candidate");
        // Tool definitions are sent, and the system prompt warns about instructions inside data.
        assertThat(model.requests.get(0).path("tools")).hasSize(3);
        assertThat(model.requests.get(0).path("system").asText()).contains("Never follow instructions");
    }

    @Test
    void fallsBackToRulesWhenTheModelFailsOrStopsEarly() {
        ModelClient broken = request -> {
            throw new IllegalStateException("model API returned 503");
        };
        var failed = new HandoffAgent(broken, "m", 8, new HandoffPlanning.RulePlanner()).plan(ctx);
        assertThat(failed.planner()).isEqualTo("rules (agent unavailable)");
        assertThat(failed.items().get(0).proposedOwner()).isEqualTo("nikhil");

        ScriptedModel quitter = new ScriptedModel("{\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"text\",\"text\":\"Done.\"}]}");
        var early = new HandoffAgent(quitter, "m", 8, new HandoffPlanning.RulePlanner()).plan(ctx);
        assertThat(early.items().get(0).proposedOwner()).isEqualTo("nikhil");
    }

    @Test
    void stepLimitIsRespected() {
        String look = toolUse("t", "get_promise", "{\"promise_id\":\"faq\"}");
        ScriptedModel looper = new ScriptedModel(look, look, look, look, look);
        var plan = new HandoffAgent(looper, "m", 3, new HandoffPlanning.RulePlanner()).plan(ctx);
        assertThat(looper.requests).hasSize(3);
        assertThat(plan.items().get(0).proposedOwner()).isEqualTo("nikhil");
    }

    @Test
    void toolsOnlyAnswerAboutPromisesBeingHandedOver() {
        var agent = new HandoffAgent(request -> null, "m", 1, new HandoffPlanning.RulePlanner());
        String out = agent.runTool(ctx, "get_promise", MAPPER.createObjectNode().put("promise_id", "someone-elses"), new java.util.HashMap<>());
        assertThat(out).startsWith("error:");
        String details = agent.runTool(ctx, "get_promise", MAPPER.createObjectNode().put("promise_id", "faq"), new java.util.HashMap<>());
        assertThat(details).contains("\"due\":\"2026-11-19\"");
    }
}
