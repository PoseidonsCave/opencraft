package com.zenith.plugin.opencraft.automation;

import com.zenith.Proxy;
import com.zenith.feature.pathfinder.PathingRequestFuture;
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
        if (blocks < 1 || blocks > 512) {
            throw new IllegalArgumentException("Movement distance must be between 1 and 512 blocks.");
        }

        final DirectionOffset offset = normalize(direction);
        final int currentX = MathHelper.floorI(BOT.getX());
        final int currentZ = MathHelper.floorI(BOT.getZ());
        final int targetX = currentX + (offset.dx() * blocks);
        final int targetZ = currentZ + (offset.dz() * blocks);

        final PathingRequestFuture future = BARITONE.pathTo(targetX, targetZ);
        if (!future.isAccepted()) {
            throw new IllegalStateException("Pathfinder rejected the directional movement request.");
        }

        watchMoveCompletion(requestId, identity, offset.label(), blocks, targetX, targetZ);

        logger.info("[OpenCraft] Started directional move {} {} blocks from ({}, {}) to ({}, {})",
            offset.label(), blocks, currentX, currentZ, targetX, targetZ);
        return "Moving " + blocks + " block(s) " + offset.label()
            + " from the current position to (" + targetX + ", " + targetZ + ").";
    }

    private void watchMoveCompletion(final String requestId,
                                     final UserIdentity identity,
                                     final String directionLabel,
                                     final int blocks,
                                     final int targetX,
                                     final int targetZ) {
        final Instant deadline = Instant.now().plus(MOVE_TIMEOUT);
        final ScheduledFuture<?>[] pollRef = new ScheduledFuture<?>[1];
        pollRef[0] = EXECUTOR.scheduleAtFixedRate(() -> {
            try {
                if (Instant.now().isAfter(deadline)) {
                    final ScheduledFuture<?> poll = pollRef[0];
                    if (poll != null) poll.cancel(false);
                    discordNotifier.notifyMilestone(requestId, identity, "cardinal",
                        "Directional move " + directionLabel + " " + blocks + " block(s) timed out.");
                    return;
                }
                if (!BARITONE.isActive()) {
                    final ScheduledFuture<?> poll = pollRef[0];
                    if (poll != null) poll.cancel(false);
                    discordNotifier.notifyMilestone(requestId, identity, "cardinal",
                        "Finished moving " + directionLabel + " " + blocks
                            + " block(s) — arrived near (" + targetX + ", " + targetZ + ").");
                }
            } catch (final Exception e) {
                final ScheduledFuture<?> poll = pollRef[0];
                if (poll != null) poll.cancel(false);
                discordNotifier.notifyMilestone(requestId, identity, "cardinal",
                    "Directional move completion watcher failed: " + e.getMessage());
            }
        }, POLL_INTERVAL_SECONDS, POLL_INTERVAL_SECONDS, TimeUnit.SECONDS);
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
