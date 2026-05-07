package com.zenith.plugin.opencraft.observe;

import com.zenith.Proxy;
import com.zenith.plugin.opencraft.automation.PatrolService;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

import java.util.List;

import static com.zenith.Globals.*;

public final class WorldStateObserver {

    private final ComponentLogger logger;
    private final PatrolService patrolService;

    public WorldStateObserver(final ComponentLogger logger,
                              final PatrolService patrolService) {
        this.logger = logger;
        this.patrolService = patrolService;
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
        long   dayTimeTicks = -1;
        String timeOfDay = "";
        String weather = "";
        boolean antiAfkEnabled = false;
        boolean antiAfkWalk = false;
        boolean antiAfkJump = false;
        boolean antiAfkSafeWalk = false;
        boolean antiAfkRotate = false;
        boolean antiAfkSwing = false;
        boolean antiAfkSneak = false;
        final List<String> patrolSummaries = patrolService.snapshotSummaries();

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

            final var chunkCache = CACHE.getChunkCache();
            final var worldTimeData = chunkCache.getWorldTimeData();
            if (worldTimeData != null) {
                dayTimeTicks = worldTimeData.toPacket().getDayTime();
                timeOfDay = formatTimeOfDay(dayTimeTicks);
            }
            weather = describeWeather(chunkCache.isRaining(),
                chunkCache.getRainStrength(), chunkCache.getThunderStrength());
        }

        antiAfkEnabled = CONFIG.client.extra.antiafk.enabled;
        antiAfkWalk = CONFIG.client.extra.antiafk.actions.walk;
        antiAfkJump = CONFIG.client.extra.antiafk.actions.jump;
        antiAfkSafeWalk = CONFIG.client.extra.antiafk.actions.safeWalk;
        antiAfkRotate = CONFIG.client.extra.antiafk.actions.rotate;
        antiAfkSwing = CONFIG.client.extra.antiafk.actions.swingHand;
        antiAfkSneak = CONFIG.client.extra.antiafk.actions.sneak;

        final boolean pathfinderActive   = BARITONE.isActive();
        final var     currentGoal        = BARITONE.currentGoal();
        final String  pathfinderGoalDesc = currentGoal != null ? currentGoal.getClass().getSimpleName() : "";
        final int nearbyPlayerCount = CACHE.getTabListCache().getTablist().size();

        final List<String> enabledModules = MODULE.getModules().stream()
            .filter(m -> m.isEnabled())
            .map(m -> m.getClass().getSimpleName())
            .sorted()
            .toList();

        return new WorldState(
            connected, inQueue, queuePosition,
            x, y, z, yaw, pitch,
            gameMode, health, dayTimeTicks, timeOfDay, weather,
            antiAfkEnabled, antiAfkWalk, antiAfkJump, antiAfkSafeWalk,
            antiAfkRotate, antiAfkSwing, antiAfkSneak,
            patrolSummaries,
            pathfinderActive, pathfinderGoalDesc,
            nearbyPlayerCount, occupiedSlots,
            enabledModules
        );
    }

    private static WorldState disconnectedState() {
        return new WorldState(
            false, false, 0,
            0, 0, 0, 0, 0,
            "unknown", null, -1, "", "",
            false, false, false, false, false, false, false,
            List.of(),
            false, "",
            0, 0,
            List.of()
        );
    }

    private static String formatTimeOfDay(final long dayTime) {
        final long ticks = Math.floorMod(dayTime, 24000);
        final int hours = (int) ((ticks / 1000 + 6) % 24);
        final int minutes = (int) ((ticks % 1000) * 60 / 1000);
        final String prefix = dayTime < 0 ? "fixed " : "";
        return String.format("%s%02d:%02d", prefix, hours, minutes);
    }

    private static String describeWeather(final boolean raining,
                                          final float rainStrength,
                                          final float thunderStrength) {
        if (thunderStrength > 0.1f) return "thunder";
        if (raining || rainStrength > 0.1f) return "rain";
        return "clear";
    }
}
