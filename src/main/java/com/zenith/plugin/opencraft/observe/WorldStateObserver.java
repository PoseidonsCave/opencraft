package com.zenith.plugin.opencraft.observe;

import com.zenith.Proxy;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

import java.util.List;

import static com.zenith.Globals.*;

/**
 * Gathers a point-in-time snapshot of ZenithProxy and game state.
 *
 * All reads are from ZenithProxy's own thread-safe caches (DataCache, BOT,
 * BARITONE, MODULE, Proxy). No network I/O is performed; this call is safe
 * to invoke on any thread.
 *
 * If any read fails (e.g. not yet connected), a safe default is used and the
 * error is logged at WARN level.
 */
public final class WorldStateObserver {

    private final ComponentLogger logger;

    public WorldStateObserver(final ComponentLogger logger) {
        this.logger = logger;
    }

    public WorldState observe() {
        try {
            return doObserve();
        } catch (final Exception e) {
            logger.warn("[OpenCraft] WorldStateObserver failed: {}", e.getMessage());
            return disconnectedState();
        }
    }

    private WorldState doObserve() {
        final var proxy = Proxy.getInstance();
        final boolean connected     = proxy.isConnected();
        final boolean inQueue       = proxy.isInQueue();
        final int     queuePosition = proxy.getQueuePosition();

        double x = 0, y = 0, z = 0;
        float  yaw = 0, pitch = 0;
        String gameMode = "unknown";
        Float  health = null;
        int    occupiedSlots = 0;

        if (connected && !inQueue) {
            x     = BOT.getX();
            y     = BOT.getY();
            z     = BOT.getZ();
            yaw   = BOT.getYaw();
            pitch = BOT.getPitch();

            final var pc = CACHE.getPlayerCache();
            if (pc.getGameMode() != null) {
                gameMode = pc.getGameMode().name().toLowerCase();
            }
            health = pc.getThePlayer().getHealth();

            final var contents = pc.getInventoryCache().getPlayerInventory().getContents();
            occupiedSlots = (int) contents.stream().filter(s -> s != null).count();
        }

        final boolean pathfinderActive   = BARITONE.isActive();
        final var     currentGoal        = BARITONE.currentGoal();
        final String  pathfinderGoalDesc = currentGoal != null ? currentGoal.getClass().getSimpleName() : "";

        // Tab list size is a reasonable proxy for nearby visible players.
        final int nearbyPlayerCount = CACHE.getTabListCache().getTablist().size();

        final List<String> enabledModules = MODULE.getModules().stream()
            .filter(m -> m.isEnabled())
            .map(m -> m.getClass().getSimpleName())
            .sorted()
            .toList();

        return new WorldState(
            connected, inQueue, queuePosition,
            x, y, z, yaw, pitch,
            gameMode, health,
            pathfinderActive, pathfinderGoalDesc,
            nearbyPlayerCount, occupiedSlots,
            enabledModules
        );
    }

    private static WorldState disconnectedState() {
        return new WorldState(
            false, false, 0,
            0, 0, 0, 0, 0,
            "unknown", null,
            false, "",
            0, 0,
            List.of()
        );
    }
}
