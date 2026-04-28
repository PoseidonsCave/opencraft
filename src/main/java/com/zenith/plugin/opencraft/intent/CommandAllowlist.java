package com.zenith.plugin.opencraft.intent;

import com.zenith.plugin.opencraft.OpenCraftConfig;
import com.zenith.plugin.opencraft.auth.UserRole;
import com.zenith.plugin.opencraft.plan.BaselineOperations;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Config-driven, deny-by-default allowlist of approved admin commands.
 * Only commands explicitly listed here (or in the baseline, when enabled)
 * may ever be executed.
 */
public final class CommandAllowlist {

    private final OpenCraftConfig config;

    public CommandAllowlist(final OpenCraftConfig config) {
        this.config = config;
    }

    private List<CommandDefinition> definitions() {
        // Operator-configured entries
        final List<CommandDefinition> defs = new ArrayList<>(
            config.allowedCommands.stream()
                .filter(c -> !c.commandId.isBlank() && !c.zenithCommand.isBlank())
                .map(c -> new CommandDefinition(
                    c.commandId,
                    c.description,
                    c.zenithCommand,
                    UserRole.fromString(c.roleRequired),
                    c.riskLevel,
                    c.confirmationRequired,
                    List.copyOf(c.redactFields),
                    c.argumentSchema == null ? java.util.Map.of() : java.util.Map.copyOf(c.argumentSchema)
                ))
                .toList()
        );

        // Merge baseline operations when enabled, skipping any already overridden by operator
        if (config.operationsEnabled && config.baselineOperationsEnabled) {
            final var operatorIds = defs.stream().map(CommandDefinition::commandId).collect(
                java.util.stream.Collectors.toSet());
            BaselineOperations.definitions().stream()
                .filter(d -> !operatorIds.contains(d.commandId()))
                .forEach(defs::add);
        }

        return List.copyOf(defs);
    }

    /** Find a command definition by its exact command ID. */
    public Optional<CommandDefinition> find(final String commandId) {
        if (commandId == null || commandId.isBlank()) return Optional.empty();
        return definitions().stream()
            .filter(d -> d.commandId().equals(commandId))
            .findFirst();
    }

    /**
     * Returns descriptions of all allowlisted commands — used ONLY in admin system prompts.
     * Does not expose zenithCommand, redactFields, or other internal details.
     */
    public List<AdminToolDescription> getAdminToolDescriptions() {
        return definitions().stream()
            .map(d -> new AdminToolDescription(d.commandId(), d.description(), d.argumentSchema()))
            .toList();
    }

    public boolean isEmpty() {
        return definitions().isEmpty();
    }

    /** Minimal view of a command exposed to the LLM in the admin system prompt. */
    public record AdminToolDescription(
        String commandId,
        String description,
        java.util.Map<String, String> argumentSchema
    ) {}
}
