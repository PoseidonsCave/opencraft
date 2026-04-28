package com.zenith.plugin.opencraft.plan;

import com.zenith.plugin.opencraft.OpenCraftConfig;
import com.zenith.plugin.opencraft.intent.CommandDefinition;
import com.zenith.plugin.opencraft.auth.UserRole;

import java.util.List;
import java.util.Map;

/**
 * Hard-coded baseline allowlist entries derived from real ZenithProxy commands.
 *
 * These entries are merged into the allowlist when
 * OpenCraftConfig.baselineOperationsEnabled is true. Operators can augment or
 * override them by adding entries with the same commandId to allowedCommands.
 *
 * All zenithCommand strings here were verified against ZenithProxy source at
 * src/main/java/com/zenith/command/impl/PathfinderCommand.java and
 * src/main/java/com/zenith/command/impl/TasksCommand.java.
 *
 * EULA note: pathfinder.mine and pathfinder.clearArea are HIGH risk and
 * require explicit confirmation because automated mining and clearing can
 * violate 2b2t and other server rules. Operators should evaluate their server's
 * ToS before enabling these.
 */
public final class BaselineOperations {

    private BaselineOperations() {}

    /** Returns all baseline operation definitions as CommandDefinition objects. */
    public static List<CommandDefinition> definitions() {
        return List.of(
            define(
                "pathfinder.status",
                "Check current pathfinder navigation status and active goal",
                "pathfinder status",
                "admin", "low", false,
                Map.of()
            ),
            define(
                "pathfinder.stop",
                "Stop any active pathfinder navigation immediately",
                "pathfinder stop",
                "admin", "low", false,
                Map.of()
            ),
            define(
                "pathfinder.goto.xz",
                "Navigate to X,Z coordinates (Y determined automatically)",
                "pathfinder goto {x} {z}",
                "admin", "medium", false,
                Map.of("x", "integer", "z", "integer")
            ),
            define(
                "pathfinder.goto.xyz",
                "Navigate to exact X,Y,Z coordinates",
                "pathfinder goto {x} {y} {z}",
                "admin", "medium", false,
                Map.of("x", "integer", "y", "integer", "z", "integer")
            ),
            define(
                "pathfinder.thisway",
                "Move N blocks in the current facing direction",
                "pathfinder thisway {blocks}",
                "admin", "medium", false,
                Map.of("blocks", "integer")
            ),
            define(
                "pathfinder.follow",
                "Follow a player by name (stops when command is cancelled)",
                "pathfinder follow {player}",
                "admin", "medium", false,
                Map.of("player", "string")
            ),
            define(
                "pathfinder.pickup",
                "Navigate toward and pick up all nearby items on the ground",
                "pathfinder pickup",
                "admin", "low", false,
                Map.of()
            ),
            define(
                "pathfinder.mine",
                "Mine all reachable blocks of the specified type. " +
                    "WARNING: automated mining may violate server rules — confirm before use.",
                "pathfinder mine {block}",
                "admin", "high", true,
                Map.of("block", "string")
            ),
            define(
                "status.query",
                "Query current ZenithProxy proxy status: connection, position, modules",
                "status",
                "admin", "low", false,
                Map.of()
            ),
            define(
                "tasks.list",
                "List all currently scheduled background tasks",
                "tasks list",
                "admin", "low", false,
                Map.of()
            ),
            define(
                "tasks.clear",
                "Remove all scheduled tasks. WARNING: this stops all background automation.",
                "tasks clear",
                "admin", "high", true,
                Map.of()
            )
        );
    }

    private static CommandDefinition define(
        final String commandId,
        final String description,
        final String zenithCommand,
        final String roleRequired,
        final String riskLevel,
        final boolean confirmationRequired,
        final Map<String, String> argumentSchema
    ) {
        return new CommandDefinition(
            commandId,
            description,
            zenithCommand,
            UserRole.fromString(roleRequired),
            riskLevel,
            confirmationRequired,
            List.of(),
            Map.copyOf(argumentSchema)
        );
    }
}
