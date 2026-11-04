package dev.promisegraph.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Handoff settings, bound from {@code promisegraph.handoff.*}.
 *
 * @param planner {@code rules} (default) or {@code agent} (Claude with tools; needs ANTHROPIC_API_KEY)
 * @param prepDays how many days before an absence to plan cover
 * @param agentMaxSteps the most model calls one handoff plan may make
 */
@ConfigurationProperties("promisegraph.handoff")
public record HandoffProperties(String planner, int prepDays, int agentMaxSteps) {

    public HandoffProperties {
        planner = planner == null || planner.isBlank() ? "rules" : planner.strip();
        if (!planner.equals("rules") && !planner.equals("agent")) {
            throw new IllegalArgumentException("promisegraph.handoff.planner must be 'rules' or 'agent', got " + planner);
        }
        if (prepDays < 0 || prepDays > 14) {
            prepDays = 3;
        }
        if (agentMaxSteps < 2 || agentMaxSteps > 30) {
            agentMaxSteps = 12;
        }
    }
}
