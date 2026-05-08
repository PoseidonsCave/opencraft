package com.zenith.plugin.opencraft.plan;

import com.zenith.plugin.opencraft.OpenCraftConfig;
import com.zenith.plugin.opencraft.intent.CommandDefinition;
import com.zenith.plugin.opencraft.auth.UserRole;

import java.util.List;
import java.util.Map;

public final class BaselineOperations {

    private BaselineOperations() {}

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
                "pathfinder.cardinal",
                "Move N blocks north, south, east, west, or along -x/-z style world directions from the current position",
                "@internal:pathfinder.cardinal {direction} {blocks}",
                "admin", "medium", false,
                Map.of("direction", "string", "blocks", "integer")
            ),
            define(
                "pathfinder.near",
                "Navigate to anywhere within a radius of the target X,Y,Z position",
                "pathfinder near {x} {y} {z} {rangeSq}",
                "admin", "medium", false,
                Map.of("x", "integer", "y", "integer", "z", "integer", "rangeSq", "integer")
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
                "patrol.once.current",
                "Walk to a random point within a radius around the current position",
                "@internal:patrol.once.current {radius}",
                "admin", "medium", false,
                Map.of("radius", "integer")
            ),
            define(
                "patrol.schedule.current",
                "Schedule recurring patrols around the current position using start and repeat delays like 30s or 3h",
                "@internal:patrol.schedule.current {taskId} {radius} {startDelay} {repeatDelay}",
                "admin", "medium", false,
                Map.of("taskId", "string", "radius", "integer", "startDelay", "string", "repeatDelay", "string")
            ),
            define(
                "patrol.cancel",
                "Cancel a scheduled patrol by id",
                "@internal:patrol.cancel {taskId}",
                "admin", "low", false,
                Map.of("taskId", "string")
            ),
            define(
                "patrol.list",
                "List the scheduled patrols currently managed by OpenCraft",
                "@internal:patrol.list",
                "admin", "low", false,
                Map.of()
            ),
            define(
                "status.query",
                "Query current ZenithProxy proxy status: connection, position, modules",
                "status",
                "admin", "low", false,
                Map.of()
            ),
            define(
                "antiafk.status",
                "Query AntiAFK module status and current action settings",
                "antiAFK",
                "admin", "low", false,
                Map.of()
            ),
            define(
                "antiafk.toggle",
                "Enable or disable the AntiAFK module",
                "antiAFK {toggle}",
                "admin", "low", false,
                Map.of("toggle", "string")
            ),
            define(
                "antiafk.walk.toggle",
                "Enable or disable AntiAFK walking",
                "antiAFK walk {toggle}",
                "admin", "low", false,
                Map.of("toggle", "string")
            ),
            define(
                "antiafk.jump.toggle",
                "Enable or disable AntiAFK jumping",
                "antiAFK jump {toggle}",
                "admin", "low", false,
                Map.of("toggle", "string")
            ),
            define(
                "antiafk.jump.onlyinwater.toggle",
                "Enable or disable AntiAFK jumping only while in water",
                "antiAFK jump onlyInWater {toggle}",
                "admin", "low", false,
                Map.of("toggle", "string")
            ),
            define(
                "antiafk.safewalk.toggle",
                "Enable or disable AntiAFK safe walk protection",
                "antiAFK safeWalk {toggle}",
                "admin", "low", false,
                Map.of("toggle", "string")
            ),
            define(
                "antiafk.rotate.toggle",
                "Enable or disable AntiAFK rotation movement",
                "antiAFK rotate {toggle}",
                "admin", "low", false,
                Map.of("toggle", "string")
            ),
            define(
                "antiafk.swing.toggle",
                "Enable or disable AntiAFK hand swinging",
                "antiAFK swing {toggle}",
                "admin", "low", false,
                Map.of("toggle", "string")
            ),
            define(
                "antiafk.sneak.toggle",
                "Enable or disable AntiAFK sneaking",
                "antiAFK sneak {toggle}",
                "admin", "low", false,
                Map.of("toggle", "string")
            ),
            define(
                "antiafk.walk.distance",
                "Set AntiAFK walking distance in blocks",
                "antiAFK walkDistance {blocks}",
                "admin", "low", false,
                Map.of("blocks", "integer")
            ),
            define(
                "tasks.list",
                "List all currently scheduled background tasks",
                "tasks list",
                "admin", "low", false,
                Map.of()
            ),
            define(
                "tasks.interval.pathfinder.thisway",
                "Schedule recurring movement in the current facing direction",
                "tasks add interval {taskId} {startDelay} {repeatDelay} pathfinder thisway {blocks}",
                "admin", "medium", false,
                Map.of("taskId", "string", "startDelay", "string", "repeatDelay", "string", "blocks", "integer")
            ),
            define(
                "tasks.interval.pathfinder.near",
                "Schedule recurring patrols within a radius of X,Y,Z",
                "tasks add interval {taskId} {startDelay} {repeatDelay} pathfinder near {x} {y} {z} {rangeSq}",
                "admin", "medium", false,
                Map.of(
                    "taskId", "string",
                    "startDelay", "string",
                    "repeatDelay", "string",
                    "x", "integer",
                    "y", "integer",
                    "z", "integer",
                    "rangeSq", "integer"
                )
            ),
            define(
                "tasks.interval.pathfinder.follow",
                "Schedule recurring follow commands for a player",
                "tasks add interval {taskId} {startDelay} {repeatDelay} pathfinder follow {player}",
                "admin", "medium", false,
                Map.of("taskId", "string", "startDelay", "string", "repeatDelay", "string", "player", "string")
            ),
            define(
                "tasks.delete",
                "Delete a scheduled task by id",
                "tasks del {taskId}",
                "admin", "low", false,
                Map.of("taskId", "string")
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
