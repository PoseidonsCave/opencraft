package com.zenith.plugin.opencraft.automation;

import com.zenith.Proxy;
import com.zenith.cache.data.entity.EntityPlayer;
import com.zenith.feature.pathfinder.PathingRequestFuture;
import com.zenith.feature.pathfinder.goals.GoalNear;
import com.zenith.plugin.opencraft.auth.UserIdentity;
import com.zenith.plugin.opencraft.discord.DiscordNotifier;
import com.zenith.util.math.MathHelper;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static com.zenith.Globals.BARITONE;
import static com.zenith.Globals.BOT;
import static com.zenith.Globals.CACHE;
import static com.zenith.Globals.EXECUTOR;

public final class CardinalMovementService {

    private static final long POLL_INTERVAL_SECONDS = 2L;
    private static final Duration MOVE_TIMEOUT = Duration.ofMinutes(5);

    private final ComponentLogger logger;
    private final DiscordNotifier discordNotifier;

    public CardinalMovementService(final ComponentLogger logger,
                                   final DiscordNotifier discordNotifier) {
        this.logger = logger;
        this.discordNotifier = discordNotifier;
    }

    public String moveFromCurrent(final String requestId,
                                  final UserIdentity identity,
                                  final String direction,
                                  final int blocks) {
        ensureInGame();
        validateDistance(blocks);

        final DirectionOffset offset = normalize(direction);
        final int currentX = MathHelper.floorI(BOT.getX());
        final int currentZ = MathHelper.floorI(BOT.getZ());
        final int targetX = currentX + (offset.dx() * blocks);
        final int targetZ = currentZ + (offset.dz() * blocks);

        pathTo(requestId, identity, "directional move", targetX, targetZ);

        logger.info("[OpenCraft] Started directional move {} {} blocks from ({}, {}) to ({}, {})",
            offset.label(), blocks, currentX, currentZ, targetX, targetZ);
        return "Moving " + blocks + " block(s) " + offset.label()
            + " from the current position to (" + targetX + ", " + targetZ + ").";
    }

    public String moveThisWay(final String requestId,
                              final UserIdentity identity,
                              final int blocks) {
        ensureInGame();
        validateDistance(blocks);

        final PathingRequestFuture future = BARITONE.thisWay(blocks);
        if (!future.isAccepted()) {
            throw new IllegalStateException("Pathfinder rejected the facing-direction movement request.");
        }

        watchMoveCompletion(requestId, identity, "facing direction", "Facing-direction move timed out.");

        logger.info("[OpenCraft] Started facing-direction move {} blocks from ({}, {}) yaw={}",
            blocks, MathHelper.floorI(BOT.getX()), MathHelper.floorI(BOT.getZ()), BOT.getYaw());
        return "Moving " + blocks + " block(s) in the current facing direction.";
    }

    public String gotoXz(final String requestId,
                         final UserIdentity identity,
                         final int x,
                         final int z) {
        ensureInGame();
        pathTo(requestId, identity, "navigation", x, z);
        logger.info("[OpenCraft] Started navigation to ({}, {})", x, z);
        return "Navigating to x=" + x + ", z=" + z + ".";
    }

    public String gotoXyz(final String requestId,
                          final UserIdentity identity,
                          final int x,
                          final int y,
                          final int z) {
        ensureInGame();
        final PathingRequestFuture future = BARITONE.pathTo(x, y, z);
        if (!future.isAccepted()) {
            throw new IllegalStateException("Pathfinder rejected the exact-coordinate navigation request.");
        }
        watchMoveCompletion(requestId, identity, "exact coordinate", "Exact-coordinate navigation timed out.");
        logger.info("[OpenCraft] Started navigation to ({}, {}, {})", x, y, z);
        return "Navigating to x=" + x + ", y=" + y + ", z=" + z + ".";
    }

    public String near(final String requestId,
                       final UserIdentity identity,
                       final int x,
                       final int y,
                       final int z,
                       final int rangeSq) {
        ensureInGame();
        if (rangeSq < 1 || rangeSq > 262144) {
            throw new IllegalArgumentException("Near rangeSq must be between 1 and 262144.");
        }
        final PathingRequestFuture future = BARITONE.pathTo(new GoalNear(x, y, z, rangeSq));
        if (!future.isAccepted()) {
            throw new IllegalStateException("Pathfinder rejected the near-position navigation request.");
        }
        watchMoveCompletion(requestId, identity, "near-position", "Near-position navigation timed out.");
        logger.info("[OpenCraft] Started navigation near ({}, {}, {}) rangeSq={}", x, y, z, rangeSq);
        return "Navigating near x=" + x + ", y=" + y + ", z=" + z + ".";
    }

    public String followPlayer(final String requestId,
                               final UserIdentity identity,
                               final String playerName) {
        ensureInGame();
        final String safeName = validatePlayerName(playerName);
        final EntityPlayer target = CACHE.getEntityCache().getPlayers().values().stream()
            .filter(player -> player.getUuid() != null)
            .filter(player -> CACHE.getTabListCache()
                .get(player.getUuid())
                .filter(entry -> entry.getName().equalsIgnoreCase(safeName))
                .isPresent())
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Player '" + safeName + "' is not currently visible."));

        BARITONE.follow(target);

        discordNotifier.notifyMilestone(requestId, identity, "pathfinder",
            "Started following " + safeName + ".");
        logger.info("[OpenCraft] Started following player {} ({})", safeName, target.getUuid());
        return "Following " + safeName + ".";
    }

    public String pickup(final String requestId,
                         final UserIdentity identity) {
        ensureInGame();
        BARITONE.pickup();
        discordNotifier.notifyMilestone(requestId, identity, "pathfinder", "Started pickup pathing.");
        logger.info("[OpenCraft] Started pickup pathing.");
        return "Picking up nearby items.";
    }

    public String stop() {
        BARITONE.stop();
        return "Stopped pathfinder navigation.";
    }

    public String status() {
        if (!Proxy.getInstance().isConnected()) {
            return "Pathfinder unavailable: bot is disconnected.";
        }
        if (Proxy.getInstance().isInQueue()) {
            return "Pathfinder unavailable: bot is in queue.";
        }
        if (!BARITONE.isActive()) {
            return "Pathfinder is idle.";
        }
        final var goal = BARITONE.currentGoal();
        return "Pathfinder is active" + (goal == null ? "." : ": " + goal + ".");
    }

    private void pathTo(final String requestId,
                        final UserIdentity identity,
                        final String label,
                        final int targetX,
                        final int targetZ) {
        final PathingRequestFuture future = BARITONE.pathTo(targetX, targetZ);
        if (!future.isAccepted()) {
            throw new IllegalStateException("Pathfinder rejected the " + label + " request.");
        }
        watchMoveCompletion(requestId, identity, label, label + " timed out.");
    }

    private void watchMoveCompletion(final String requestId,
                                     final UserIdentity identity,
                                     final String moveLabel,
                                     final String timeoutDetail) {
        final Instant deadline = Instant.now().plus(MOVE_TIMEOUT);
        final ScheduledFuture<?>[] pollRef = new ScheduledFuture<?>[1];
        pollRef[0] = EXECUTOR.scheduleAtFixedRate(() -> {
            try {
                if (Instant.now().isAfter(deadline)) {
                    final ScheduledFuture<?> poll = pollRef[0];
                    if (poll != null) poll.cancel(false);
                    discordNotifier.notifyMilestone(requestId, identity, "pathfinder",
                        timeoutDetail);
                    return;
                }
                if (!BARITONE.isActive()) {
                    final ScheduledFuture<?> poll = pollRef[0];
                    if (poll != null) poll.cancel(false);
                    discordNotifier.notifyMilestone(requestId, identity, "pathfinder",
                        "Finished " + moveLabel + ".");
                }
            } catch (final Exception e) {
                final ScheduledFuture<?> poll = pollRef[0];
                if (poll != null) poll.cancel(false);
                discordNotifier.notifyMilestone(requestId, identity, "pathfinder",
                    "Pathfinder completion watcher failed: " + e.getMessage());
            }
        }, POLL_INTERVAL_SECONDS, POLL_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    private static void validateDistance(final int blocks) {
        if (blocks < 1 || blocks > 512) {
            throw new IllegalArgumentException("Movement distance must be between 1 and 512 blocks.");
        }
    }

    private static String validatePlayerName(final String playerName) {
        final String value = playerName == null ? "" : playerName.strip();
        if (value.length() < 1 || value.length() > 16) {
            throw new IllegalArgumentException("Player name must be between 1 and 16 characters.");
        }
        for (int i = 0; i < value.length(); i++) {
            final char ch = value.charAt(i);
            if (!(Character.isLetterOrDigit(ch) || ch == '_')) {
                throw new IllegalArgumentException("Player name may only use letters, digits, or underscores.");
            }
        }
        return value;
    }

    static DirectionOffset normalize(final String rawDirection) {
        if (rawDirection == null) {
            throw new IllegalArgumentException("Direction is required.");
        }
        final String direction = rawDirection.strip().toLowerCase(Locale.ROOT)
            .replace(" ", "")
            .replace("_", "");
        return switch (direction) {
            case "north", "n", "-z", "z-", "negz", "negativez" ->
                new DirectionOffset(0, -1, "north");
            case "south", "s", "z", "+z", "z+", "posz", "positivez" ->
                new DirectionOffset(0, 1, "south");
            case "east", "e", "x", "+x", "x+", "posx", "positivex" ->
                new DirectionOffset(1, 0, "east");
            case "west", "w", "-x", "x-", "negx", "negativex" ->
                new DirectionOffset(-1, 0, "west");
            default ->
                throw new IllegalArgumentException("Direction must be north, south, east, west, -x, or -z style.");
        };
    }

    private static void ensureInGame() {
        if (!Proxy.getInstance().isConnected() || Proxy.getInstance().isInQueue()) {
            throw new IllegalStateException("The bot must be connected and in-game to move.");
        }
    }

    record DirectionOffset(int dx, int dz, String label) {
    }
}
