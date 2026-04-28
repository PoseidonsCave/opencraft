package com.zenith.plugin.opencraft.plan;

import com.zenith.plugin.opencraft.intent.CommandIntent;

import java.util.List;

/**
 * An LLM-proposed multi-step operation plan.
 *
 * The plan is produced by the LLM and represents its proposal for how to
 * fulfil a user's request. It is NOT authoritative — every step is still
 * validated by CommandExecutor before execution, and all RBAC and allowlist
 * rules apply regardless of what the plan contains.
 *
 * steps: ordered list of command intents to execute sequentially.
 * risk: "LOW", "MEDIUM", or "HIGH" — LLM self-assessment.
 * confirmationRequired: whether admin approval is required before execution.
 * costEstimate: rough estimated token spend for the full operation.
 * estimatedDuration: human-readable duration estimate (e.g., "~30 seconds").
 * reasoning: brief LLM explanation of why this plan satisfies the request.
 */
public record OperationalPlan(
    List<CommandIntent> steps,
    String risk,
    boolean confirmationRequired,
    int costEstimate,
    String estimatedDuration,
    String reasoning
) {

    /** True when the plan's own risk assessment or confirmation flag warrants a gate. */
    public boolean requiresApproval() {
        return confirmationRequired || "HIGH".equalsIgnoreCase(risk) || "MEDIUM".equalsIgnoreCase(risk);
    }

    /**
     * Returns a compact human-readable summary safe to whisper to the user.
     * Does NOT include step-level zenithCommand strings (those are Java-side only).
     */
    public String summary() {
        final var sb = new StringBuilder();
        sb.append(steps.size()).append("-step plan");
        sb.append(" | risk=").append(risk.toUpperCase());
        sb.append(" | est. ").append(estimatedDuration);
        if (costEstimate > 0) {
            sb.append(" | ~").append(costEstimate).append(" tokens");
        }
        sb.append(":\n");
        for (int i = 0; i < steps.size(); i++) {
            sb.append(i + 1).append(". ").append(steps.get(i).explanation()).append("\n");
        }
        if (!reasoning.isBlank()) {
            sb.append("Reasoning: ").append(reasoning);
        }
        return sb.toString().strip();
    }
}
