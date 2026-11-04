package dev.promisegraph.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Negotiation settings, bound from {@code promisegraph.negotiation.*}.
 *
 * @param enabled false: slips only warn the owners downstream, as in Phase 2
 * @param planner {@code rules} (default) or {@code agent} (Claude with tools; needs ANTHROPIC_API_KEY)
 * @param minTeams a slip affecting at least this many teams starts a negotiation (a customer promise always does)
 * @param capacity the most open promises one person should carry; plans may not go above it
 * @param approvalHours how long approvers have to choose, per round
 * @param reminderHours when approvers who haven't chosen get a reminder
 * @param maxRounds rounds of plans before the negotiation gives up
 * @param agentMaxSteps the most model calls one round of the option agent may make
 * @param customers customer names; a promise that mentions one counts as made to a customer
 * @param teamChecks ask each team's workflow to hold the people a plan adds
 */
@ConfigurationProperties("promisegraph.negotiation")
public record NegotiationProperties(boolean enabled, String planner, int minTeams, int capacity, int approvalHours, int reminderHours,
        int maxRounds, int agentMaxSteps, List<String> customers, boolean teamChecks) {

    public NegotiationProperties {
        planner = planner == null || planner.isBlank() ? "rules" : planner.strip();
        if (!planner.equals("rules") && !planner.equals("agent")) {
            throw new IllegalArgumentException("promisegraph.negotiation.planner must be 'rules' or 'agent', got " + planner);
        }
        minTeams = minTeams < 1 ? 2 : minTeams;
        capacity = capacity < 1 ? 8 : capacity;
        approvalHours = approvalHours < 1 ? 72 : approvalHours;
        reminderHours = reminderHours < 1 ? 24 : reminderHours;
        maxRounds = maxRounds < 1 || maxRounds > 5 ? 3 : maxRounds;
        agentMaxSteps = agentMaxSteps < 2 || agentMaxSteps > 30 ? 12 : agentMaxSteps;
        customers = customers == null ? List.of() : List.copyOf(customers);
    }
}
