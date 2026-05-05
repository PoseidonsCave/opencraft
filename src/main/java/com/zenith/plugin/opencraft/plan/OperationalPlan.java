package com.zenith.plugin.opencraft.plan;

import com.zenith.plugin.opencraft.intent.CommandIntent;

import java.util.List;

public record OperationalPlan(
    List<CommandIntent> steps,
    String risk,
    boolean confirmationRequired,
    int costEstimate,
    String estimatedDuration,
    String reasoning
) {

        public boolean requiresApproval() {
        return confirmationRequired || "HIGH".equalsIgnoreCase(risk) || "MEDIUM".equalsIgnoreCase(risk);
    }

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
