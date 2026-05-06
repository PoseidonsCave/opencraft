package com.zenith.plugin.opencraft.command;

import com.zenith.feature.api.ProfileData;
import com.zenith.feature.whitelist.PlayerListsManager;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.zenith.command.api.Command;
import com.zenith.command.api.CommandCategory;
import com.zenith.command.api.CommandContext;
import com.zenith.command.api.CommandSources;
import com.zenith.command.api.CommandUsage;
import com.zenith.plugin.opencraft.debug.ChatDebugRecorder;
import com.zenith.plugin.opencraft.OpenCraftConfig;
import com.zenith.plugin.opencraft.OpenCraftModule;
import com.zenith.plugin.opencraft.auth.UserRole;
import com.zenith.plugin.opencraft.audit.AuditEvent;
import com.zenith.plugin.opencraft.audit.AuditLogger;
import com.zenith.plugin.opencraft.update.PluginUpdateService;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.List;

import static com.zenith.Globals.saveConfig;

public class OpenCraftCommand extends Command {

    private final OpenCraftConfig     config;
    private final OpenCraftModule     module;
    private final PluginUpdateService updateService;
    private final AuditLogger         auditLogger;
    private final ComponentLogger     logger;
    private final UserKeyResolver     userKeyResolver;
    private final ChatDebugRecorder   chatDebugRecorder;

    public OpenCraftCommand(final OpenCraftConfig config,
                            final OpenCraftModule module,
                            final PluginUpdateService updateService,
                            final AuditLogger auditLogger,
                            final ComponentLogger logger,
                            final ChatDebugRecorder chatDebugRecorder) {
        this.config        = config;
        this.module        = module;
        this.updateService = updateService;
        this.auditLogger   = auditLogger;
        this.logger        = logger;
        this.userKeyResolver = new UserKeyResolver(this::lookupProfileByUsername);
        this.chatDebugRecorder = chatDebugRecorder;
    }

    @Override
    public CommandUsage commandUsage() {
        return CommandUsage.builder()
            .name("llm")
            .category(CommandCategory.MODULE)
            .description("OpenCraft — LLM assistant plugin management")
            .usageLines(
                "status",
                "enable",
                "disable",
                "user list",
                "user add UUID_OR_USERNAME MEMBER|ADMIN",
                "user remove UUID_OR_USERNAME",
                "user promote UUID_OR_USERNAME",
                "user demote UUID_OR_USERNAME",
                "allow list",
                "allow add COMMAND_ID ROLE RISK CONFIRM_TRUE|FALSE DESCRIPTION -- ZENITH_COMMAND",
                "allow remove COMMAND_ID",
                "update",
                "update check",
                "update stage",
                "debug recent",
                "debug clear",
                "audit prune",
                "config"
            )
            .build();
    }

    @Override
    public LiteralArgumentBuilder<CommandContext> register() {
        return command("llm")
            .requires(this::validateManagementAccess)
            .then(literal("status").executes(c -> {
                c.getSource().getEmbed()
                    .title("OpenCraft Status")
                    .addField("Module enabled", String.valueOf(module.isEnabled()), true)
                    .addField("Provider",       config.providerName,                true)
                    .addField("Model",          config.model,                       true)
                    .addField("Prefix",         config.prefix,                      true)
                    .addField("Public chat",    String.valueOf(config.publicChatEnabled), true)
                    .addField("Whisper input",  String.valueOf(config.whisperEnabled),    true)
                    .addField("Update channel", config.updateChannel,               true)
                    .addField("Update status",  String.valueOf(updateService.getStatus()), true);
                return OK;
            }))
            .then(literal("enable")
                .executes(c -> {
                    module.setEnabled(true);
                    c.getSource().getEmbed().title("OpenCraft").description("Module enabled.");
                    return OK;
                }))
            .then(literal("disable")
                .executes(c -> {
                    module.setEnabled(false);
                    c.getSource().getEmbed().title("OpenCraft").description("Module disabled.");
                    return OK;
                }))
            .then(literal("user")
                .then(literal("list").executes(c -> {
                    final String description = renderUserList();
                    c.getSource().getEmbed()
                        .title("OpenCraft Users")
                        .description(description)
                        .addField("Entries", String.valueOf(config.users.size()), true)
                        .addField("Username fallback", String.valueOf(config.allowUsernameOnlyFallback), true);
                    return OK;
                }))
                .then(literal("add")
                    .then(argument("key", StringArgumentType.string())
                        .then(argument("role", enumStrings("member", "admin"))
                            .executes(c -> {
                                final String rawKey = StringArgumentType.getString(c, "key");
                                final UserRole role = UserRole.fromString(StringArgumentType.getString(c, "role"));
                                final UserKeyResolver.Resolution resolution =
                                    userKeyResolver.resolveForStorage(rawKey, role == UserRole.ADMIN);
                                if (!resolution.valid()) {
                                    c.getSource().getEmbed()
                                        .title("OpenCraft User Add")
                                        .description(resolution.error())
                                        .errorColor();
                                    return ERROR;
                                }

                                final String storageKey = resolution.storageKey();
                                removeResolvedUsernameAlias(resolution);
                                config.users.put(storageKey, role.name().toLowerCase(Locale.ROOT));
                                saveConfig();

                                final var embed = c.getSource().getEmbed()
                                    .title("OpenCraft User Added")
                                    .addField("Key", storageKey, false)
                                    .addField("Role", role.name().toLowerCase(Locale.ROOT), true);
                                if (resolution.resolvedUuidFromUsername()) {
                                    embed.addField("Resolved from username", resolution.resolvedUsername(), false);
                                }
                                embed.addField("Persistence", "Saved to plugins/config/opencraft.json", false);
                                return OK;
                            }))))
                .then(literal("remove")
                    .then(argument("key", StringArgumentType.string())
                        .executes(c -> {
                            final String rawKey = StringArgumentType.getString(c, "key");
                            final String keyToRemove = findExistingUserKey(rawKey);
                            final String removed = keyToRemove == null ? null : config.users.remove(keyToRemove);

                            if (removed == null) {
                                c.getSource().getEmbed()
                                    .title("OpenCraft User Remove")
                                    .description("No user entry found for: " + rawKey)
                                    .errorColor();
                                return ERROR;
                            }

                            saveConfig();
                            c.getSource().getEmbed()
                                .title("OpenCraft User Removed")
                                .addField("Key", keyToRemove, false)
                                .addField("Previous role", removed, true)
                                .addField("Persistence", "Saved to plugins/config/opencraft.json", false);
                            return OK;
                        })))
                .then(literal("promote")
                    .then(argument("key", StringArgumentType.string())
                        .executes((com.mojang.brigadier.context.CommandContext<CommandContext> c) ->
                            updateUserRole(c, StringArgumentType.getString(c, "key"), UserRole.ADMIN))))
                .then(literal("demote")
                    .then(argument("key", StringArgumentType.string())
                        .executes((com.mojang.brigadier.context.CommandContext<CommandContext> c) ->
                            updateUserRole(c, StringArgumentType.getString(c, "key"), UserRole.MEMBER)))))
            .then(literal("allow")
                .then(literal("list").executes(c -> {
                    c.getSource().getEmbed()
                        .title("OpenCraft Allowlist")
                        .description(renderAllowList())
                        .addField("Entries", String.valueOf(config.allowedCommands.size()), true);
                    return OK;
                }))
                .then(literal("add")
                    .then(argument("commandId", StringArgumentType.string())
                        .then(argument("role", enumStrings("admin", "member"))
                            .then(argument("risk", enumStrings("low", "medium", "high"))
                                .then(argument("confirm", enumStrings("true", "false"))
                                    .then(argument("spec", StringArgumentType.greedyString())
                                        .executes(c -> {
                                            final String commandId = StringArgumentType.getString(c, "commandId").strip();
                                            if (!isValidCommandId(commandId)) {
                                                c.getSource().getEmbed()
                                                    .title("OpenCraft Allow Add")
                                                    .description("command_id must contain only letters, digits, '.', '_', or '-'.")
                                                    .errorColor();
                                                return ERROR;
                                            }
                                            if (findAllowedCommand(commandId) != null) {
                                                c.getSource().getEmbed()
                                                    .title("OpenCraft Allow Add")
                                                    .description("An allowlist entry already exists for: " + commandId)
                                                    .errorColor();
                                                return ERROR;
                                            }

                                            final String role = StringArgumentType.getString(c, "role").strip().toLowerCase(Locale.ROOT);
                                            final String risk = StringArgumentType.getString(c, "risk").strip().toLowerCase(Locale.ROOT);
                                            final boolean confirm = Boolean.parseBoolean(StringArgumentType.getString(c, "confirm"));
                                            final String spec = StringArgumentType.getString(c, "spec");
                                            final String[] split = spec.split("\\s+--\\s+", 2);
                                            if (split.length != 2 || split[0].isBlank() || split[1].isBlank()) {
                                                c.getSource().getEmbed()
                                                    .title("OpenCraft Allow Add")
                                                    .description("Use: /llm allow add COMMAND_ID ROLE RISK CONFIRM_TRUE|FALSE DESCRIPTION -- ZENITH_COMMAND")
                                                    .errorColor();
                                                return ERROR;
                                            }

                                            final OpenCraftConfig.AllowedCommandConfig entry = new OpenCraftConfig.AllowedCommandConfig();
                                            entry.commandId = commandId;
                                            entry.description = split[0].strip();
                                            entry.zenithCommand = split[1].strip();
                                            entry.roleRequired = role;
                                            entry.riskLevel = risk;
                                            entry.confirmationRequired = confirm;
                                            config.allowedCommands.add(entry);
                                            saveConfig();

                                            c.getSource().getEmbed()
                                                .title("OpenCraft Allowlist Added")
                                                .addField("command_id", entry.commandId, false)
                                                .addField("role", entry.roleRequired, true)
                                                .addField("risk", entry.riskLevel, true)
                                                .addField("confirm", String.valueOf(entry.confirmationRequired), true)
                                                .addField("description", entry.description, false)
                                                .addField("zenithCommand", entry.zenithCommand, false)
                                                .addField("Persistence", "Saved to plugins/config/opencraft.json", false);
                                            return OK;
                                        })))))))
                .then(literal("remove")
                    .then(argument("commandId", StringArgumentType.string())
                        .executes(c -> {
                            final String commandId = StringArgumentType.getString(c, "commandId").strip();
                            final OpenCraftConfig.AllowedCommandConfig existing = findAllowedCommand(commandId);
                            if (existing == null) {
                                c.getSource().getEmbed()
                                    .title("OpenCraft Allow Remove")
                                    .description("No allowlist entry found for: " + commandId)
                                    .errorColor();
                                return ERROR;
                            }

                            config.allowedCommands.remove(existing);
                            saveConfig();
                            c.getSource().getEmbed()
                                .title("OpenCraft Allowlist Removed")
                                .addField("command_id", existing.commandId, false)
                                .addField("description", existing.description, false)
                                .addField("Persistence", "Saved to plugins/config/opencraft.json", false);
                            return OK;
                        }))))
            .then(literal("update")
                .executes(c -> {
                    final var result = updateService.checkForUpdates();
                    c.getSource().getEmbed().title("OpenCraft Update").description(result.message());
                    return OK;
                })
                .then(literal("check").executes(c -> {
                    final var result = updateService.checkForUpdates();
                    c.getSource().getEmbed().title("OpenCraft Update Check").description(result.message());
                    return OK;
                }))
                .then(literal("stage").executes(c -> {
                    final var result = updateService.checkAndStageUpdate();
                    c.getSource().getEmbed().title("OpenCraft Update Stage").description(result.message());
                    return OK;
                }))
            )
            .then(literal("audit")
                .then(literal("prune").executes(c -> {
                    auditLogger.pruneOldEntries();
                    c.getSource().getEmbed()
                        .title("OpenCraft Audit")
                        .description("Audit log pruning scheduled (entries older than "
                            + config.auditRetentionDays + " days).");
                    return OK;
                }))
            )
            .then(literal("debug")
                .then(literal("recent").executes(c -> {
                    final List<ChatDebugRecorder.DebugEvent> recent = chatDebugRecorder.recent(20);
                    final String description = recent.isEmpty()
                        ? "No recent chat debug events."
                        : recent.stream()
                            .map(event -> event.timestamp() + " | " + event.stage() + " | " + event.detail())
                            .reduce((left, right) -> left + "\n" + right)
                            .orElse("No recent chat debug events.");
                    c.getSource().getEmbed()
                        .title("OpenCraft Debug")
                        .description(description);
                    return OK;
                }))
                .then(literal("clear").executes(c -> {
                    chatDebugRecorder.clear();
                    c.getSource().getEmbed()
                        .title("OpenCraft Debug")
                        .description("Cleared recent chat debug events.");
                    return OK;
                }))
            )
            .then(literal("config").executes(c -> {
                c.getSource().getEmbed()
                    .title("OpenCraft Config")
                    .addField("prefix",              config.prefix,                                     true)
                    .addField("publicChatEnabled",   String.valueOf(config.publicChatEnabled),          true)
                    .addField("whisperEnabled",      String.valueOf(config.whisperEnabled),             true)
                    .addField("model",               config.model,                                      true)
                    .addField("providerName",        config.providerName,                               true)
                    .addField("providerBaseUrl",     config.providerBaseUrl,                            false)
                    .addField("apiKeyEnvVar",        config.apiKeyEnvVar + " (value hidden)",           false)
                    .addField("userCooldownMs",      String.valueOf(config.userCooldownMs),             true)
                    .addField("userHourlyLimit",     String.valueOf(config.userHourlyLimit),            true)
                    .addField("allowedCommands",     config.allowedCommands.size() + " entries",        true)
                    .addField("discordAuditEnabled", String.valueOf(config.discordAuditEnabled),        true)
                    .addField("users",               config.users.size() + " entries",                  true);
                return OK;
            }));
    }

    private boolean validateManagementAccess(final CommandContext context) {
        if (!validateCommandSource(context, List.of(CommandSources.TERMINAL, CommandSources.DISCORD))) {
            auditDeniedManagementAttempt(context, "Command source is not allowed for /llm management");
            return false;
        }
        if (!validateAccountOwner(context)) {
            auditDeniedManagementAttempt(context, "Not authorized: account owner role required");
            return false;
        }
        return true;
    }

    private void auditDeniedManagementAttempt(final CommandContext context, final String reason) {
        try {
            final String sourceName = context.getSource() == null ? "unknown" : context.getSource().name();
            final String requestId = "mgmt-" + UUID.randomUUID().toString().substring(0, 8);
            auditLogger.log(AuditEvent.requestDenied(requestId, sourceName, reason));
            logger.warn("[OpenCraft] Denied /llm management command from source={} reason={}", sourceName, reason);
        } catch (final Exception ignored) {
        }
    }

    private String renderUserList() {
        if (config.users.isEmpty()) {
            return "No users configured.";
        }

        final StringBuilder builder = new StringBuilder();
        for (final Map.Entry<String, String> entry : new TreeMap<>(config.users).entrySet()) {
            if (builder.length() > 0) builder.append('\n');
            builder.append(entry.getKey()).append(" -> ").append(entry.getValue());
        }
        return builder.toString();
    }

    private String renderAllowList() {
        if (config.allowedCommands.isEmpty()) {
            return "No allowlist entries configured.";
        }

        final StringBuilder builder = new StringBuilder();
        for (final OpenCraftConfig.AllowedCommandConfig entry : config.allowedCommands.stream()
            .sorted((left, right) -> left.commandId.compareToIgnoreCase(right.commandId))
            .toList()) {
            if (builder.length() > 0) builder.append('\n');
            builder.append(entry.commandId)
                .append(" -> ")
                .append(entry.roleRequired)
                .append(" / ")
                .append(entry.riskLevel)
                .append(" / confirm=")
                .append(entry.confirmationRequired);
        }
        return builder.toString();
    }

    private int updateUserRole(final com.mojang.brigadier.context.CommandContext<CommandContext> context,
                               final String rawKey,
                               final UserRole targetRole) {
        final UserKeyResolver.Resolution resolution =
            userKeyResolver.resolveForStorage(rawKey, targetRole == UserRole.ADMIN);
        if (!resolution.valid()) {
            context.getSource().getEmbed()
                .title("OpenCraft User Update")
                .description(resolution.error())
                .errorColor();
            return ERROR;
        }

        final String keyToUpdate = findExistingUserKey(rawKey, resolution);
        final String previousRole = keyToUpdate == null ? null : config.users.get(keyToUpdate);
        if (previousRole == null) {
            context.getSource().getEmbed()
                .title("OpenCraft User Update")
                .description("No user entry found for: " + rawKey)
                .errorColor();
            return ERROR;
        }

        final String newRole = targetRole.name().toLowerCase(Locale.ROOT);
        if (!keyToUpdate.equals(resolution.storageKey())) {
            config.users.remove(keyToUpdate);
        }
        removeResolvedUsernameAlias(resolution);
        config.users.put(resolution.storageKey(), newRole);
        saveConfig();

        final var embed = context.getSource().getEmbed()
            .title("OpenCraft User Updated")
            .addField("Key", resolution.storageKey(), false)
            .addField("Previous role", previousRole, true)
            .addField("New role", newRole, true);
        if (resolution.resolvedUuidFromUsername()) {
            embed.addField("Resolved from username", resolution.resolvedUsername(), false);
        }
        embed.addField("Persistence", "Saved to plugins/config/opencraft.json", false);
        return OK;
    }

    private OpenCraftConfig.AllowedCommandConfig findAllowedCommand(final String commandId) {
        for (final OpenCraftConfig.AllowedCommandConfig entry : config.allowedCommands) {
            if (entry.commandId.equals(commandId)) {
                return entry;
            }
        }
        return null;
    }

    private static boolean isValidCommandId(final String commandId) {
        if (commandId == null || commandId.isBlank()) {
            return false;
        }
        for (int index = 0; index < commandId.length(); index++) {
            final char ch = commandId.charAt(index);
            final boolean valid = Character.isLetterOrDigit(ch) || ch == '.' || ch == '_' || ch == '-';
            if (!valid) return false;
        }
        return true;
    }

    private String findExistingUserKey(final String rawKey) {
        final String normalizedKey = UserKeyResolver.normalizeUserKey(rawKey);
        if (normalizedKey != null && config.users.containsKey(normalizedKey)) {
            return normalizedKey;
        }
        final UserKeyResolver.Resolution resolution = userKeyResolver.resolveForStorage(rawKey, false);
        return findExistingUserKey(rawKey, resolution);
    }

    private String findExistingUserKey(final String rawKey, final UserKeyResolver.Resolution resolution) {
        if (resolution.valid() && config.users.containsKey(resolution.storageKey())) {
            return resolution.storageKey();
        }
        final String normalizedKey = UserKeyResolver.normalizeUserKey(rawKey);
        if (normalizedKey != null && config.users.containsKey(normalizedKey)) {
            return normalizedKey;
        }
        if (resolution.valid() && resolution.resolvedUsername() != null && config.users.containsKey(resolution.resolvedUsername())) {
            return resolution.resolvedUsername();
        }
        if (resolution.valid() && resolution.inputUsername() != null && config.users.containsKey(resolution.inputUsername())) {
            return resolution.inputUsername();
        }
        final String strippedRawKey = rawKey == null ? null : rawKey.strip();
        if (strippedRawKey == null || strippedRawKey.isBlank()) {
            return null;
        }
        return config.users.containsKey(strippedRawKey) ? strippedRawKey : null;
    }

    private void removeResolvedUsernameAlias(final UserKeyResolver.Resolution resolution) {
        if (!resolution.resolvedUuidFromUsername()) {
            return;
        }
        if (resolution.inputUsername() != null && !resolution.inputUsername().equals(resolution.storageKey())) {
            config.users.remove(resolution.inputUsername());
        }
        if (resolution.resolvedUsername() != null && !resolution.resolvedUsername().equals(resolution.storageKey())) {
            config.users.remove(resolution.resolvedUsername());
        }
    }

    private Optional<ProfileData> lookupProfileByUsername(final String username) {
        final Optional<ProfileData> cachedProfile = lookupProfileFromTabList(username);
        if (cachedProfile.isPresent()) {
            return cachedProfile;
        }
        return PlayerListsManager.getProfileFromUsername(username);
    }

    private Optional<ProfileData> lookupProfileFromTabList(final String username) {
        try {
            return com.zenith.Globals.CACHE.getTabListCache().getFromName(username)
                .map(entry -> {
                    final UUID uuid = entry.getProfile() != null ? entry.getProfile().getId() : entry.getProfileId();
                    final String resolvedName = entry.getProfile() != null ? entry.getProfile().getName() : entry.getName();
                    return new SimpleProfileData(resolvedName, uuid);
                });
        } catch (final Exception ignored) {
            return Optional.empty();
        }
    }

    private record SimpleProfileData(String name, UUID uuid) implements ProfileData {
    }
}
