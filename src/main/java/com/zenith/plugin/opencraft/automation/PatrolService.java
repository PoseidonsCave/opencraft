package com.zenith.plugin.opencraft.automation;

import com.zenith.Proxy;
import com.zenith.feature.pathfinder.goals.GoalXZ;
import com.zenith.feature.pathfinder.PathingRequestFuture;
import com.zenith.plugin.opencraft.auth.UserIdentity;
import com.zenith.plugin.opencraft.discord.DiscordNotifier;
import com.zenith.util.math.MathHelper;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static com.zenith.Globals.BARITONE;
import static com.zenith.Globals.BOT;
import static com.zenith.Globals.EXECUTOR;

public final class PatrolService {

    private static final int MIN_RADIUS = 2;
    private static final int MAX_RADIUS = 512;
    private static final long POLL_INTERVAL_SECONDS = 2L;
    private static final Duration RUN_TIMEOUT = Duration.ofMinutes(15);

    private final ComponentLogger logger;
    private final DiscordNotifier discordNotifier;
    private final ConcurrentHashMap<String, PatrolTask> tasks = new ConcurrentHashMap<>();

    public PatrolService(final ComponentLogger logger,
                         final DiscordNotifier discordNotifier) {
        this.logger = logger;
        this.discordNotifier = discordNotifier;
    }

    public String patrolOnceCurrent(final String requestId,
                                    final UserIdentity identity,
                                    final int radius) {
        final int safeRadius = validateRadius(radius);
        ensureInGame();

        final PatrolAnchor anchor = currentAnchor();
        final GoalXZ target = randomGoal(anchor, safeRadius);
        final PathingRequestFuture future = BARITONE.pathTo(target);
        if (!future.isAccepted()) {
            notifyFailure(requestId, identity, "One-shot patrol was rejected by pathfinder.");
            throw new IllegalStateException("Patrol request was rejected by pathfinder.");
        }
        notifyMilestone(requestId, identity, "patrol",
            "Started one-shot patrol to " + target.x() + "," + target.z()
                + " within " + safeRadius + " blocks of the current position.");
        watchRunCompletion(requestId, identity, "patrol", "One-shot patrol finished.",
            "One-shot patrol timed out waiting for pathfinder to finish.", null);
        logger.info("[OpenCraft] Started one-shot patrol from ({}, {}, {}) to ({}, {}) radius={}",
            anchor.x(), anchor.y(), anchor.z(), target.x(), target.z(), safeRadius);
        return "Started a patrol move within " + safeRadius + " blocks of the current position.";
    }

    public String scheduleCurrent(final String requestId,
                                  final UserIdentity identity,
                                  final String taskId,
                                  final int radius,
                                  final String startDelay,
                                  final String repeatDelay) {
        final String safeTaskId = validateTaskId(taskId);
        final int safeRadius = validateRadius(radius);
        ensureInGame();
        if (tasks.containsKey(safeTaskId)) {
            throw new IllegalArgumentException("A patrol with id '" + safeTaskId + "' already exists.");
        }

        final Duration start = PatrolIntervalParser.parse(startDelay);
        final Duration repeat = PatrolIntervalParser.parse(repeatDelay);
        final PatrolAnchor anchor = currentAnchor();
        final PatrolTask patrolTask = new PatrolTask(
            requestId,
            identity,
            safeTaskId,
            anchor,
            safeRadius,
            repeat
        );

        final ScheduledFuture<?> future = EXECUTOR.scheduleAtFixedRate(
            () -> tickTask(patrolTask),
            start.toMillis(),
            repeat.toMillis(),
            TimeUnit.MILLISECONDS
        );
        patrolTask.future = future;
        tasks.put(safeTaskId, patrolTask);

        notifyMilestone(requestId, identity, "patrol",
            "Scheduled patrol '" + safeTaskId + "' around " + anchor.x() + "," + anchor.y() + "," + anchor.z()
                + " radius=" + safeRadius + " start=" + startDelay + " repeat=" + repeatDelay + ".");
        logger.info("[OpenCraft] Scheduled patrol id={} anchor=({}, {}, {}) radius={} start={} repeat={}",
            safeTaskId, anchor.x(), anchor.y(), anchor.z(), safeRadius, startDelay, repeatDelay);
        return "Scheduled patrol '" + safeTaskId + "' around the current position every "
            + repeatDelay + " after " + startDelay + ".";
    }

    public String cancel(final String taskId) {
        final String safeTaskId = validateTaskId(taskId);
        final PatrolTask task = tasks.remove(safeTaskId);
        if (task == null) {
            throw new IllegalArgumentException("No patrol with id '" + safeTaskId + "' exists.");
        }
        if (task.future != null) {
            task.future.cancel(false);
        }
        if (task.runPollFuture != null) {
            task.runPollFuture.cancel(false);
        }
        notifyMilestone(task.requestId(), task.identity, "patrol",
            "Cancelled patrol '" + safeTaskId + "'.");
        logger.info("[OpenCraft] Cancelled patrol id={}", safeTaskId);
        return "Cancelled patrol '" + safeTaskId + "'.";
    }

    public String list() {
        final List<String> summaries = snapshotSummaries();
        if (summaries.isEmpty()) {
            return "No patrols are scheduled.";
        }
        return "Active patrols: " + String.join(" | ", summaries);
    }

    public List<String> snapshotSummaries() {
        return tasks.values().stream()
            .sorted(Comparator.comparing(task -> task.taskId))
            .map(PatrolTask::summary)
            .toList();
    }

    private void tickTask(final PatrolTask task) {
        try {
            if (!Proxy.getInstance().isConnected() || Proxy.getInstance().isInQueue()) {
                return;
            }
            if (BARITONE.isActive()) {
                logger.debug("[OpenCraft] Skipping patrol tick for id={} because pathfinder is busy.",
                    task.taskId);
                return;
            }

            final GoalXZ target = randomGoal(task.anchor, task.radius);
            task.lastRunAt = Instant.now();
            task.lastTargetX = target.x();
            task.lastTargetZ = target.z();
            final PathingRequestFuture future = BARITONE.pathTo(target);
            if (!future.isAccepted()) {
            notifyFailure(task.requestId(), task.identity,
                "Patrol '" + task.taskId + "' was rejected by pathfinder for target "
                        + target.x() + "," + target.z() + ".");
                return;
            }
            notifyMilestone(task.requestId(), task.identity, "patrol",
                "Patrol '" + task.taskId + "' started a run to " + target.x() + "," + target.z() + ".");
            watchRunCompletion(task.requestId(), task.identity, "patrol",
                "Patrol '" + task.taskId + "' finished its run to " + target.x() + "," + target.z() + ".",
                "Patrol '" + task.taskId + "' timed out waiting for pathfinder to finish.",
                task);
            logger.info("[OpenCraft] Patrol id={} pathing to ({}, {}) from anchor ({}, {}, {})",
                task.taskId, target.x(), target.z(),
                task.anchor.x(), task.anchor.y(), task.anchor.z());
        } catch (final Exception e) {
            notifyFailure(task.requestId(), task.identity,
                "Patrol '" + task.taskId + "' failed: " + e.getMessage());
            logger.warn("[OpenCraft] Patrol tick failed for id={}: {}", task.taskId, e.getMessage());
        }
    }

    private void watchRunCompletion(final String requestId,
                                    final UserIdentity identity,
                                    final String sourceType,
                                    final String successDetail,
                                    final String timeoutDetail,
                                    final PatrolTask task) {
        final Instant deadline = Instant.now().plus(RUN_TIMEOUT);
        final ScheduledFuture<?>[] pollRef = new ScheduledFuture<?>[1];
        pollRef[0] = EXECUTOR.scheduleAtFixedRate(() -> {
            try {
                if (task != null && tasks.get(task.taskId) != task) {
                    final ScheduledFuture<?> poll = pollRef[0];
                    if (poll != null) poll.cancel(false);
                    return;
                }
                if (Instant.now().isAfter(deadline)) {
                    final ScheduledFuture<?> poll = pollRef[0];
                    if (poll != null) poll.cancel(false);
                    notifyFailure(requestId, identity, timeoutDetail);
                    return;
                }
                if (!BARITONE.isActive()) {
                    final ScheduledFuture<?> poll = pollRef[0];
                    if (poll != null) poll.cancel(false);
                    notifyMilestone(requestId, identity, sourceType, successDetail);
                }
            } catch (final Exception e) {
                final ScheduledFuture<?> poll = pollRef[0];
                if (poll != null) poll.cancel(false);
                notifyFailure(requestId, identity, "Patrol completion watcher failed: " + e.getMessage());
            }
        }, POLL_INTERVAL_SECONDS, POLL_INTERVAL_SECONDS, TimeUnit.SECONDS);
        if (task != null) {
            final ScheduledFuture<?> previous = task.runPollFuture;
            if (previous != null) {
                previous.cancel(false);
            }
            task.runPollFuture = pollRef[0];
        }
    }

    private void notifyMilestone(final String requestId,
                                 final UserIdentity identity,
                                 final String sourceType,
                                 final String detail) {
        discordNotifier.notifyMilestone(requestId, identity, sourceType, detail);
    }

    private void notifyFailure(final String requestId,
                               final UserIdentity identity,
                               final String detail) {
        discordNotifier.notifyMilestone(requestId, identity, "patrol", detail);
    }

    private static PatrolAnchor currentAnchor() {
        return new PatrolAnchor(
            MathHelper.floorI(BOT.getX()),
            MathHelper.floorI(BOT.getY()),
            MathHelper.floorI(BOT.getZ())
        );
    }

    private static GoalXZ randomGoal(final PatrolAnchor anchor, final int radius) {
        final ThreadLocalRandom random = ThreadLocalRandom.current();
        int x = anchor.x();
        int z = anchor.z();
        for (int attempts = 0; attempts < 8; attempts++) {
            final double angle = random.nextDouble(0.0d, Math.PI * 2.0d);
            final double distance = Math.sqrt(random.nextDouble()) * radius;
            final int dx = (int) Math.round(Math.cos(angle) * distance);
            final int dz = (int) Math.round(Math.sin(angle) * distance);
            if (dx == 0 && dz == 0) continue;
            x = anchor.x() + dx;
            z = anchor.z() + dz;
            break;
        }
        if (x == anchor.x() && z == anchor.z()) {
            x = anchor.x() + Math.max(1, radius);
        }
        return new GoalXZ(x, z);
    }

    private static int validateRadius(final int radius) {
        if (radius < MIN_RADIUS || radius > MAX_RADIUS) {
            throw new IllegalArgumentException("Patrol radius must be between "
                + MIN_RADIUS + " and " + MAX_RADIUS + " blocks.");
        }
        return radius;
    }

    private static String validateTaskId(final String taskId) {
        final String value = taskId == null ? "" : taskId.strip();
        if (value.isBlank()) {
            throw new IllegalArgumentException("Patrol task id is required.");
        }
        for (int i = 0; i < value.length(); i++) {
            final char ch = value.charAt(i);
            final boolean valid = Character.isLetterOrDigit(ch) || ch == '.' || ch == '_' || ch == '-';
            if (!valid) {
                throw new IllegalArgumentException("Patrol task id may only use letters, digits, '.', '_', or '-'.");
            }
        }
        return value;
    }

    private static void ensureInGame() {
        if (!Proxy.getInstance().isConnected() || Proxy.getInstance().isInQueue()) {
            throw new IllegalStateException("The bot must be connected and in-game to patrol.");
        }
    }

    private record PatrolAnchor(int x, int y, int z) {}

    private static final class PatrolTask {
        private final String taskRequestId;
        private final UserIdentity identity;
        private final String taskId;
        private final PatrolAnchor anchor;
        private final int radius;
        private final Duration repeatDelay;
        private volatile Instant lastRunAt;
        private volatile Integer lastTargetX;
        private volatile Integer lastTargetZ;
        private volatile ScheduledFuture<?> future;
        private volatile ScheduledFuture<?> runPollFuture;

        private PatrolTask(final String taskRequestId,
                           final UserIdentity identity,
                           final String taskId,
                           final PatrolAnchor anchor,
                           final int radius,
                           final Duration repeatDelay) {
            this.taskRequestId = taskRequestId;
            this.identity = identity;
            this.taskId = taskId;
            this.anchor = anchor;
            this.radius = radius;
            this.repeatDelay = repeatDelay;
        }

        private String requestId() {
            return taskRequestId + ":" + taskId;
        }

        private String summary() {
            final String target = lastTargetX == null || lastTargetZ == null
                ? "pending"
                : lastTargetX + "," + lastTargetZ;
            return String.format(Locale.ROOT,
                "%s[r=%d anchor=%d,%d,%d every=%s last=%s target=%s]",
                taskId,
                radius,
                anchor.x(), anchor.y(), anchor.z(),
                formatDuration(repeatDelay),
                lastRunAt == null ? "never" : lastRunAt.toString(),
                target
            );
        }

        private static String formatDuration(final Duration duration) {
            Objects.requireNonNull(duration);
            if (duration.toMillis() % 1000L != 0L) {
                final long ticks = Math.max(1L, Math.round(duration.toMillis() / 50.0d));
                return ticks + "t";
            }
            final long seconds = duration.toSeconds();
            if (seconds % 86400L == 0L) return (seconds / 86400L) + "d";
            if (seconds % 3600L == 0L) return (seconds / 3600L) + "h";
            if (seconds % 60L == 0L) return (seconds / 60L) + "m";
            return seconds + "s";
        }
    }
}
