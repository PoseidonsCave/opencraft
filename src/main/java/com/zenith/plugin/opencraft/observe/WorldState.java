package com.zenith.plugin.opencraft.observe;

import org.jspecify.annotations.Nullable;

import java.util.List;

public record WorldState(
    boolean connected,
    boolean inQueue,
    int     queuePosition,
    double  x,
    double  y,
    double  z,
    float   yaw,
    float   pitch,
    String  gameMode,
    @Nullable Float health,
    long    dayTimeTicks,
    String  timeOfDay,
    String  weather,
    boolean antiAfkEnabled,
    boolean antiAfkWalk,
    boolean antiAfkJump,
    boolean antiAfkSafeWalk,
    boolean antiAfkRotate,
    boolean antiAfkSwing,
    boolean antiAfkSneak,
    List<String> patrolSummaries,
    boolean pathfinderActive,
    String  pathfinderGoalDesc,
    int     nearbyPlayerCount,
    int     occupiedInventorySlots,
    List<String> enabledModules
) {
        public String toPromptBlock() {
        final var sb = new StringBuilder();
        sb.append("WORLD STATE (read-only snapshot at request time):\n");

        if (!connected) {
            sb.append("  Status        : Disconnected — no game operations possible.\n");
            return sb.toString();
        }

        if (inQueue) {
            sb.append("  Status        : In queue, position ").append(queuePosition).append("\n");
            sb.append("  Note          : Pathfinder and in-game commands are unavailable while queued.\n");
            return sb.toString();
        }

        sb.append("  Status        : In game\n");
        sb.append("  Position      : x=").append((int) x)
            .append(", y=").append((int) y)
            .append(", z=").append((int) z).append("\n");
        sb.append("  Facing        : yaw=").append(String.format("%.1f", yaw))
            .append(", pitch=").append(String.format("%.1f", pitch)).append("\n");
        sb.append("  Game mode     : ").append(gameMode).append("\n");

        if (health != null) {
            sb.append("  Health        : ").append(String.format("%.1f/20.0", health)).append("\n");
        }
        if (!timeOfDay.isBlank()) {
            sb.append("  In-game time  : ").append(timeOfDay);
            if (dayTimeTicks >= 0) {
                sb.append(" [").append(dayTimeTicks).append(" ticks]");
            }
            sb.append("\n");
        }
        if (!weather.isBlank()) {
            sb.append("  Weather       : ").append(weather).append("\n");
        }
        sb.append("  AntiAFK       : ").append(antiAfkEnabled ? "enabled" : "disabled").append("\n");
        if (antiAfkEnabled) {
            sb.append("  AntiAFK modes : ")
                .append("walk=").append(antiAfkWalk)
                .append(", jump=").append(antiAfkJump)
                .append(", safeWalk=").append(antiAfkSafeWalk)
                .append(", rotate=").append(antiAfkRotate)
                .append(", swing=").append(antiAfkSwing)
                .append(", sneak=").append(antiAfkSneak)
                .append("\n");
        }
        if (!patrolSummaries.isEmpty()) {
            sb.append("  Patrol tasks  : ").append(String.join(" | ", patrolSummaries)).append("\n");
        } else {
            sb.append("  Patrol tasks  : none\n");
        }

        sb.append("  Pathfinder    : ")
            .append(pathfinderActive ? "active — " + pathfinderGoalDesc : "idle").append("\n");
        sb.append("  Nearby players: ").append(nearbyPlayerCount)
            .append(" (tab list count)\n");
        sb.append("  Inventory     : ").append(occupiedInventorySlots)
            .append(" slot(s) occupied\n");

        if (!enabledModules.isEmpty()) {
            sb.append("  Active modules: ").append(String.join(", ", enabledModules)).append("\n");
        } else {
            sb.append("  Active modules: none\n");
        }

        return sb.toString();
    }

        public boolean canAct() {
        return connected && !inQueue;
    }

        public static WorldState disconnected() {
        return new WorldState(false, false, 0, 0, 0, 0, 0, 0,
            "unknown", null, -1, "", "",
            false, false, false, false, false, false, false,
            java.util.List.of(),
            false, "", 0, 0, java.util.List.of());
    }
}
