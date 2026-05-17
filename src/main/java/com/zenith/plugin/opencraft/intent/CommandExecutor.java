package com.zenith.plugin.opencraft.intent;

import com.zenith.command.api.CommandContext;
import com.zenith.command.api.CommandSources;
import com.zenith.plugin.opencraft.OpenCraftConfig;
import com.zenith.plugin.opencraft.automation.CardinalMovementService;
import com.zenith.plugin.opencraft.automation.PatrolService;
import com.zenith.plugin.opencraft.audit.AuditEvent;
import com.zenith.plugin.opencraft.audit.AuditLogger;
import com.zenith.plugin.opencraft.auth.UserIdentity;
import com.zenith.plugin.opencraft.auth.UserRole;
import com.zenith.plugin.opencraft.discord.DiscordNotifier;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

import static com.zenith.Globals.COMMAND;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public final class CommandExecutor {
    private static final Pattern SAFE_ARG = Pattern.compile("[^a-zA-Z0-9_\\-.]");
    private static final String INTERNAL_PREFIX = "@internal:";

    private final CommandAllowlist commandAllowlist;
    private final OpenCraftConfig  config;
    private final CardinalMovementService cardinalMovementService;
    private final PatrolService    patrolService;
    private final AuditLogger      auditLogger;
    private final DiscordNotifier  discordNotifier;
    private final ComponentLogger  logger;

        private final ConcurrentHashMap<UUID, PendingConfirmation> pendingConfirmations =
        new ConcurrentHashMap<>();

    public CommandExecutor(final OpenCraftConfig config,
                           final CommandAllowlist commandAllowlist,
                           final CardinalMovementService cardinalMovementService,
                           final PatrolService patrolService,
                           final AuditLogger auditLogger,
                           final DiscordNotifier discordNotifier,
                           final ComponentLogger logger) {
        this.config           = config;
        this.commandAllowlist = commandAllowlist;
        this.cardinalMovementService = cardinalMovementService;
        this.patrolService    = patrolService;
        this.auditLogger      = auditLogger;
        this.discordNotifier  = discordNotifier;
        this.logger           = logger;
    }

        public ExecutionResult execute(final CommandIntent intent,
                                   final UserIdentity identity,
                                   final String requestId) {
        return execute(intent, identity, requestId, null);
    }

        public ExecutionResult execute(final CommandIntent intent,
                                   final UserIdentity identity,
                                   final String requestId,
                                   final String originalRequest) {
        if (identity.role() != UserRole.ADMIN) {
            logger.warn("[OpenCraft] req={} Non-admin {} attempted command execution.",
                requestId, identity.auditLabel());
            auditLogger.log(AuditEvent.commandDenied(requestId, identity, "Insufficient role", intent.commandId()));
            return ExecutionResult.denied("Administrative commands require admin role.");
        }
        final CommandIntent effectiveIntent = normalizeIntent(intent, originalRequest);
        final Optional<CommandDefinition> defOpt = commandAllowlist.find(effectiveIntent.commandId());
        if (defOpt.isEmpty()) {
            logger.warn("[OpenCraft] req={} Command '{}' not in allowlist.",
                requestId, effectiveIntent.commandId());
            auditLogger.log(AuditEvent.commandDenied(requestId, identity, "Not in allowlist", effectiveIntent.commandId()));
            return ExecutionResult.denied("That command is not available.");
        }

        final CommandDefinition def = defOpt.get();
        if (!identity.role().satisfies(def.roleRequired())) {
            auditLogger.log(AuditEvent.commandDenied(requestId, identity, "Role requirement", effectiveIntent.commandId()));
            return ExecutionResult.denied("Insufficient role for this command.");
        }
        final String argError = validateArguments(effectiveIntent.arguments(), def);
        if (argError != null) {
            logger.warn("[OpenCraft] req={} Argument validation failed for '{}': {}",
                requestId, effectiveIntent.commandId(), argError);
            auditLogger.log(AuditEvent.commandDenied(requestId, identity, "Arg validation: " + argError, effectiveIntent.commandId()));
            return ExecutionResult.denied("Command arguments are invalid.");
        }
        if (def.isHighRisk() && identity.uuid() != null) {
            final Instant expiresAt = Instant.now().plus(
                Duration.ofSeconds(Math.max(5, config.confirmationTimeoutSeconds))
            );
            final PendingConfirmation pending = new PendingConfirmation(effectiveIntent, def, expiresAt);
            pendingConfirmations.put(identity.uuid(), pending);

            auditLogger.log(AuditEvent.commandPendingConfirmation(requestId, identity, effectiveIntent.commandId()));
            discordNotifier.notifyCommandPending(requestId, identity, effectiveIntent);

            return ExecutionResult.needsConfirmation(pending,
                "[OC] High-risk command: " + def.description() + ". " +
                "Reply '" + config.prefix + " confirm' within " + Math.max(5, config.confirmationTimeoutSeconds) +
                " seconds to proceed, or '" + config.prefix + " cancel'.");
        }
        return doExecute(effectiveIntent, def, identity, requestId);
    }

        public ExecutionResult confirm(final UserIdentity identity, final String requestId) {
        if (identity.role() != UserRole.ADMIN || identity.uuid() == null) {
            return ExecutionResult.denied("Only admins can confirm commands.");
        }

        final PendingConfirmation pending = pendingConfirmations.remove(identity.uuid());
        if (pending == null) {
            return ExecutionResult.denied("No pending command to confirm.");
        }
        if (pending.isExpired()) {
            return ExecutionResult.expired();
        }

        return doExecute(pending.intent(), pending.definition(), identity, requestId);
    }

        public boolean cancel(final UserIdentity identity) {
        if (identity.uuid() == null) return false;
        return pendingConfirmations.remove(identity.uuid()) != null;
    }

    public boolean hasPendingConfirmation(final UserIdentity identity) {
        if (identity.uuid() == null) return false;
        final PendingConfirmation p = pendingConfirmations.get(identity.uuid());
        if (p == null) return false;
        if (p.isExpired()) { pendingConfirmations.remove(identity.uuid()); return false; }
        return true;
    }

    private ExecutionResult doExecute(final CommandIntent intent,
                                      final CommandDefinition def,
                                      final UserIdentity identity,
                                      final String requestId) {
        final String command = interpolateCommand(def.zenithCommand(), intent.arguments());

        logger.info("[OpenCraft] req={} Admin {} executing: {} (mapped from '{}')",
            requestId, identity.auditLabel(), def.commandId(), command);

        try {
            final String rawResult;
            if (def.zenithCommand().startsWith(INTERNAL_PREFIX)) {
                rawResult = executeInternal(def.commandId(), intent.arguments(), identity, requestId);
            } else {
                COMMAND.execute(CommandContext.create(command, CommandSources.TERMINAL));
                rawResult = successMessage(def.commandId(), intent.arguments());
            }

            final String redacted  = redact(rawResult, def.redactFields());

            auditLogger.log(AuditEvent.commandExecuted(requestId, identity, intent.commandId(), redacted));
            discordNotifier.notifyCommandExecuted(requestId, identity, intent, redacted);

            return ExecutionResult.success("[OC] Done: " + redacted);

        } catch (final Exception e) {
            logger.warn("[OpenCraft] req={} Command execution failed: {}", requestId, e.getMessage());
            auditLogger.log(AuditEvent.commandFailed(requestId, identity, intent.commandId(), e.getMessage()));
            discordNotifier.notifyCommandFailed(requestId, identity, intent, e.getMessage());
            return ExecutionResult.failed("[OC] The command could not be completed. See server logs.");
        }
    }

    private CommandIntent normalizeIntent(final CommandIntent intent,
                                          final String originalRequest) {
        final CommandIntent normalized = CommandIntentNormalizer.normalize(intent, originalRequest);
        if (normalized != null && !normalized.commandId().equals(intent.commandId())) {
            logger.info("[OpenCraft] Normalized command intent '{}' -> '{}'",
                intent.commandId(), normalized.commandId());
        }
        return normalized == null ? intent : normalized;
    }

    private String executeInternal(final String commandId,
                                   final Map<String, String> args,
                                   final UserIdentity identity,
                                   final String requestId) {
        return switch (commandId) {
            case "pathfinder.status" ->
                cardinalMovementService.status();
            case "pathfinder.stop" ->
                cardinalMovementService.stop();
            case "pathfinder.goto.xz" ->
                cardinalMovementService.gotoXz(
                    requestId,
                    identity,
                    Integer.parseInt(args.get("x").strip()),
                    Integer.parseInt(args.get("z").strip())
                );
            case "pathfinder.goto.xyz" ->
                cardinalMovementService.gotoXyz(
                    requestId,
                    identity,
                    Integer.parseInt(args.get("x").strip()),
                    Integer.parseInt(args.get("y").strip()),
                    Integer.parseInt(args.get("z").strip())
                );
            case "pathfinder.thisway" ->
                cardinalMovementService.moveThisWay(
                    requestId,
                    identity,
                    Integer.parseInt(args.get("blocks").strip())
                );
            case "pathfinder.cardinal" ->
                cardinalMovementService.moveFromCurrent(
                    requestId,
                    identity,
                    args.get("direction").strip(),
                    Integer.parseInt(args.get("blocks").strip())
                );
            case "pathfinder.near" ->
                cardinalMovementService.near(
                    requestId,
                    identity,
                    Integer.parseInt(args.get("x").strip()),
                    Integer.parseInt(args.get("y").strip()),
                    Integer.parseInt(args.get("z").strip()),
                    Integer.parseInt(args.get("rangeSq").strip())
                );
            case "pathfinder.follow" ->
                cardinalMovementService.followPlayer(
                    requestId,
                    identity,
                    args.get("player").strip()
                );
            case "pathfinder.pickup" ->
                cardinalMovementService.pickup(requestId, identity);
            case "patrol.once.current" ->
                patrolService.patrolOnceCurrent(requestId, identity,
                    Integer.parseInt(args.get("radius").strip()));
            case "patrol.schedule.current" ->
                patrolService.scheduleCurrent(
                    requestId,
                    identity,
                    args.get("taskId").strip(),
                    Integer.parseInt(args.get("radius").strip()),
                    args.get("startDelay").strip(),
                    args.get("repeatDelay").strip()
                );
            case "patrol.cancel" ->
                patrolService.cancel(args.get("taskId").strip());
            case "patrol.list" ->
                patrolService.list();
            default ->
                throw new IllegalArgumentException("Unknown internal command: " + commandId);
        };
    }

    private static String successMessage(final String commandId,
                                         final Map<String, String> args) {
        return switch (commandId) {
            case "pathfinder.thisway" ->
                "Moving " + args.get("blocks") + " block(s) in the current facing direction.";
            case "pathfinder.goto.xz" ->
                "Navigating to x=" + args.get("x") + ", z=" + args.get("z") + ".";
            case "pathfinder.goto.xyz" ->
                "Navigating to x=" + args.get("x") + ", y=" + args.get("y") + ", z=" + args.get("z") + ".";
            case "pathfinder.near" ->
                "Navigating near x=" + args.get("x") + ", y=" + args.get("y")
                    + ", z=" + args.get("z") + ".";
            case "pathfinder.follow" ->
                "Following " + args.get("player") + ".";
            case "pathfinder.pickup" ->
                "Picking up nearby items.";
            case "pathfinder.stop" ->
                "Stopped pathfinder navigation.";
            case "tasks.interval.pathfinder.thisway",
                 "tasks.interval.pathfinder.near",
                 "tasks.interval.pathfinder.follow" ->
                "Scheduled recurring task '" + args.get("taskId") + "'.";
            case "tasks.delete" ->
                "Deleted task '" + args.get("taskId") + "'.";
            case "tasks.clear" ->
                "Cleared all scheduled tasks.";
            default ->
                "[command dispatched: " + commandId + "]";
        };
    }

        private static String validateArguments(final Map<String, String> args,
                                             final CommandDefinition def) {
        if (def.argumentSchema().isEmpty()) {
            if (!args.isEmpty()) return "No arguments expected";
            return null;
        }
        for (final Map.Entry<String, String> schemaEntry : def.argumentSchema().entrySet()) {
            final String paramName = schemaEntry.getKey();
            final String paramType = schemaEntry.getValue();
            if (!args.containsKey(paramName)) {
                return "Missing required argument: " + paramName;
            }
            final String value = args.get(paramName);
            if (value == null || value.isBlank()) {
                return "Blank value for argument: " + paramName;
            }
            if ("integer".equalsIgnoreCase(paramType)) {
                try { Integer.parseInt(value.strip()); } catch (final NumberFormatException nfe) {
                    return "Argument '" + paramName + "' must be an integer";
                }
            }
            if (SAFE_ARG.matcher(value).find()) {
                return "Argument '" + paramName + "' contains disallowed characters";
            }
        }
        return null;
    }

        private static String interpolateCommand(final String template,
                                              final Map<String, String> args) {
        String result = template;
        for (final Map.Entry<String, String> e : args.entrySet()) {
            result = result.replace("{" + e.getKey() + "}", e.getValue());
        }
        return result;
    }

        static String redact(final String text, final List<String> fields) {
        if (text == null || fields.isEmpty()) return text;
        String result = text;
        for (final String field : fields) {
            result = result.replaceAll("(?i)" + Pattern.quote(field) + "\\s*[:=]\\s*\\S+",
                field + ": [REDACTED]");
        }
        return result;
    }
}
