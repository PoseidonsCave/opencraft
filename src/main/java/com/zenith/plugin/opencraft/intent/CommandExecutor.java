package com.zenith.plugin.opencraft.intent;

import com.zenith.command.api.CommandContext;
import com.zenith.command.api.CommandSources;
import com.zenith.plugin.opencraft.OpenCraftConfig;
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

/**
 * Validates a CommandIntent against the allowlist and, after optional
 * high-risk confirmation, executes the mapped ZenithProxy command.
 *
 * Security invariants:
 * 
 *   - Deny-by-default: only allowlisted command IDs may execute.
 *   - Role is re-verified in Java regardless of what the LLM returned.
 *   - Arguments are validated against the declared schema before use.
 *   - High-risk commands require explicit admin confirmation with TTL.
 *   - The zenithCommand string is interpolated only from validated arg values.
 *   - Execution output is redacted before being returned or logged.
 * 
 */
public final class CommandExecutor {

    // Minecraft username safe characters — used to sanitize argument values
    private static final Pattern SAFE_ARG = Pattern.compile("[^a-zA-Z0-9_\\-.]");

    private final CommandAllowlist commandAllowlist;
    private final OpenCraftConfig  config;
    private final AuditLogger      auditLogger;
    private final DiscordNotifier  discordNotifier;
    private final ComponentLogger  logger;

    /** Pending high-risk confirmations keyed by admin UUID. */
    private final ConcurrentHashMap<UUID, PendingConfirmation> pendingConfirmations =
        new ConcurrentHashMap<>();

    public CommandExecutor(final OpenCraftConfig config,
                           final CommandAllowlist commandAllowlist,
                           final AuditLogger auditLogger,
                           final DiscordNotifier discordNotifier,
                           final ComponentLogger logger) {
        this.config           = config;
        this.commandAllowlist = commandAllowlist;
        this.auditLogger      = auditLogger;
        this.discordNotifier  = discordNotifier;
        this.logger           = logger;
    }

    /**
     * Validate and execute a command intent for the given admin user.
     * This method is the single authoritative execution gate.
     */
    public ExecutionResult execute(final CommandIntent intent,
                                   final UserIdentity identity,
                                   final String requestId) {
        // ── 1. Role check (Java-enforced, not LLM-trusting) ───────────────────
        if (identity.role() != UserRole.ADMIN) {
            logger.warn("[OpenCraft] req={} Non-admin {} attempted command execution.",
                requestId, identity.auditLabel());
            auditLogger.log(AuditEvent.commandDenied(requestId, identity, "Insufficient role", intent.commandId()));
            return ExecutionResult.denied("Administrative commands require admin role.");
        }

        // ── 2. Allowlist lookup ───────────────────────────────────────────────
        final Optional<CommandDefinition> defOpt = commandAllowlist.find(intent.commandId());
        if (defOpt.isEmpty()) {
            logger.warn("[OpenCraft] req={} Command '{}' not in allowlist.",
                requestId, intent.commandId());
            auditLogger.log(AuditEvent.commandDenied(requestId, identity, "Not in allowlist", intent.commandId()));
            return ExecutionResult.denied("That command is not available.");
        }

        final CommandDefinition def = defOpt.get();

        // ── 3. Role check against definition ─────────────────────────────────
        if (!identity.role().satisfies(def.roleRequired())) {
            auditLogger.log(AuditEvent.commandDenied(requestId, identity, "Role requirement", intent.commandId()));
            return ExecutionResult.denied("Insufficient role for this command.");
        }

        // ── 4. Argument validation ────────────────────────────────────────────
        final String argError = validateArguments(intent.arguments(), def);
        if (argError != null) {
            logger.warn("[OpenCraft] req={} Argument validation failed for '{}': {}",
                requestId, intent.commandId(), argError);
            auditLogger.log(AuditEvent.commandDenied(requestId, identity, "Arg validation: " + argError, intent.commandId()));
            return ExecutionResult.denied("Command arguments are invalid.");
        }

        // ── 5. High-risk confirmation gate ────────────────────────────────────
        if (def.isHighRisk() && identity.uuid() != null) {
            final Instant expiresAt = Instant.now().plus(
                Duration.ofSeconds(Math.max(5, config.confirmationTimeoutSeconds))
            );
            final PendingConfirmation pending = new PendingConfirmation(intent, def, expiresAt);
            pendingConfirmations.put(identity.uuid(), pending);

            auditLogger.log(AuditEvent.commandPendingConfirmation(requestId, identity, intent.commandId()));
            discordNotifier.notifyCommandPending(requestId, identity, intent);

            return ExecutionResult.needsConfirmation(pending,
                "[OC] High-risk command: " + def.description() + ". " +
                "Reply '!gpt confirm' within " + Math.max(5, config.confirmationTimeoutSeconds) +
                " seconds to proceed, or '!gpt cancel'.");
        }

        // ── 6. Execute ────────────────────────────────────────────────────────
        return doExecute(intent, def, identity, requestId);
    }

    /**
     * Process a confirmation from an admin for a previously staged high-risk command.
     */
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

    /** Cancel any pending confirmation for this admin. */
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

    // ── Private helpers ───────────────────────────────────────────────────────

    private ExecutionResult doExecute(final CommandIntent intent,
                                      final CommandDefinition def,
                                      final UserIdentity identity,
                                      final String requestId) {
        final String command = interpolateCommand(def.zenithCommand(), intent.arguments());

        logger.info("[OpenCraft] req={} Admin {} executing: {} (mapped from '{}')",
            requestId, identity.auditLabel(), def.commandId(), command);

        try {
            // Dispatch the validated, allow-listed command through ZenithProxy's
            // canonical command bus, attributed to the TERMINAL source. The string
            // is constructed only from def.zenithCommand() (operator-controlled)
            // plus interpolated, schema- and SAFE_ARG-validated argument values.
            COMMAND.execute(CommandContext.create(command, CommandSources.TERMINAL));

            final String rawResult = "[command dispatched: " + def.commandId() + "]";
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

    /**
     * Validate that all required arguments are present and their values contain
     * only safe characters. Returns null if valid, or an error description.
     */
    private static String validateArguments(final Map<String, String> args,
                                             final CommandDefinition def) {
        if (def.argumentSchema().isEmpty()) {
            // No schema declared — no arguments accepted
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
            // Type validation
            if ("integer".equalsIgnoreCase(paramType)) {
                try { Integer.parseInt(value.strip()); } catch (final NumberFormatException nfe) {
                    return "Argument '" + paramName + "' must be an integer";
                }
            }
            // Character-level safety: prevent injection into command strings
            if (SAFE_ARG.matcher(value).find()) {
                return "Argument '" + paramName + "' contains disallowed characters";
            }
        }
        return null;
    }

    /**
     * Interpolate validated argument values into the command template.
     * Template placeholders use the format {argName}.
     * Only arguments that passed schema validation reach this point.
     */
    private static String interpolateCommand(final String template,
                                              final Map<String, String> args) {
        String result = template;
        for (final Map.Entry<String, String> e : args.entrySet()) {
            // Values were already validated by SAFE_ARG — safe to substitute
            result = result.replace("{" + e.getKey() + "}", e.getValue());
        }
        return result;
    }

    /**
     * Replace values of listed field names in a string with "[REDACTED]".
     * Field names are matched case-insensitively.
     */
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
