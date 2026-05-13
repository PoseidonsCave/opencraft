package com.zenith.plugin.opencraft.command;

import com.zenith.feature.api.ProfileData;
import com.zenith.feature.whitelist.PlayerListsManager;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.zenith.command.api.Command;
import com.zenith.command.api.CommandCategory;
import com.zenith.command.api.CommandContext;
import com.zenith.command.api.CommandSources;
import com.zenith.command.api.CommandUsage;
import com.zenith.Proxy;
import com.zenith.plugin.opencraft.agent.AgentChallenge;
import com.zenith.plugin.opencraft.agent.AgentNetworkService;
import com.zenith.plugin.opencraft.agent.AgentNetworkStatus;
import com.zenith.plugin.opencraft.agent.FleetRunSnapshot;
import com.zenith.plugin.opencraft.agent.FleetRunStatus;
import com.zenith.plugin.opencraft.agent.FleetStepSnapshot;
import com.zenith.plugin.opencraft.agent.FleetStepStatus;
import com.zenith.plugin.opencraft.agent.FleetTaskEnvelope;
import com.zenith.plugin.opencraft.agent.NodeProfile;
import com.zenith.plugin.opencraft.debug.ChatDebugRecorder;
import com.zenith.plugin.opencraft.OpenCraftConfig;
import com.zenith.plugin.opencraft.OpenCraftModule;
import com.zenith.plugin.opencraft.auth.UserRole;
import com.zenith.plugin.opencraft.audit.AuditEvent;
import com.zenith.plugin.opencraft.audit.AuditLogger;
import com.zenith.plugin.opencraft.chat.ChatHandler;
import com.zenith.plugin.opencraft.update.PluginUpdateService;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.List;
import java.util.function.ToIntFunction;

import static com.zenith.Globals.saveConfig;

public class OpenCraftCommand extends Command {

    private final OpenCraftConfig     config;
    private final OpenCraftModule     module;
    private final PluginUpdateService updateService;
    private final AuditLogger         auditLogger;
    private final ComponentLogger     logger;
    private final UserKeyResolver     userKeyResolver;
    private final ChatDebugRecorder   chatDebugRecorder;
    private final ChatHandler         chatHandler;
    private final AgentNetworkService agentNetworkService;

    public OpenCraftCommand(final OpenCraftConfig config,
                            final OpenCraftModule module,
                            final PluginUpdateService updateService,
                            final AuditLogger auditLogger,
                            final ComponentLogger logger,
                            final ChatDebugRecorder chatDebugRecorder,
                            final ChatHandler chatHandler,
                            final AgentNetworkService agentNetworkService) {
        this.config        = config;
        this.module        = module;
        this.updateService = updateService;
        this.auditLogger   = auditLogger;
        this.logger        = logger;
        this.userKeyResolver = new UserKeyResolver(this::lookupProfileByUsername);
        this.chatDebugRecorder = chatDebugRecorder;
        this.chatHandler   = chatHandler;
        this.agentNetworkService = agentNetworkService;
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
                "profile",
                "profile manager|agent|hybrid",
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
                "debug whisper USERNAME MESSAGE",
                "agent status",
                "agent challenge PEER_ID",
                "agent run list",
                "agent run show RUN_ID",
                "agent run create SUMMARY -- PEER_IDS",
                "agent run start RUN_ID",
                "agent run finish RUN_ID [DETAIL]",
                "agent run fail RUN_ID REASON",
                "agent run cancel RUN_ID [DETAIL]",
                "agent run step RUN_ID PEER_ID COMMAND_ID SUMMARY",
                "agent run step-status RUN_ID STEP_ID STATUS DETAIL",
                "agent billing TARGET_COUNT",
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
                final AgentNetworkStatus agentStatus = agentNetworkService.status();
                c.getSource().getEmbed()
                    .title("OpenCraft Status")
                    .addField("Module enabled", String.valueOf(module.isEnabled()), true)
                    .addField("Provider",       config.providerName,                true)
                    .addField("Model",          config.model,                       true)
                    .addField("Profile",        config.profile,                     true)
                    .addField("Prefix",         config.prefix,                      true)
                    .addField("Public chat",    String.valueOf(config.publicChatEnabled), true)
                    .addField("Whisper input",  String.valueOf(config.whisperEnabled),    true)
                    .addField("Update channel", config.updateChannel,               true)
                    .addField("Update status",  String.valueOf(updateService.getStatus()), true)
                    .addField("Agent mode",     String.valueOf(agentStatus.enabled()), true)
                    .addField("Agent peers",    String.valueOf(agentStatus.configuredPeers()), true)
                    .addField("Shared billing", String.valueOf(agentStatus.sharedBillingAcrossPeers()), true);
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
            .then(literal("profile")
                .executes(c -> {
                    c.getSource().getEmbed()
                        .title("OpenCraft Profile")
                        .description("Current node profile for orchestration behavior.")
                        .addField("profile", config.profile, true)
                        .addField("canCoordinateFleet", String.valueOf(agentNetworkService.canCoordinateFleet()), true)
                        .addField("canAcceptFleetTasks", String.valueOf(agentNetworkService.canAcceptFleetTasks()), true);
                    return OK;
                })
                .then(argument("mode", enumStrings("manager", "agent", "hybrid"))
                    .executes(c -> {
                        final NodeProfile profile = NodeProfile.fromString(StringArgumentType.getString(c, "mode"));
                        config.profile = profile.configValue();
                        saveConfig();
                        c.getSource().getEmbed()
                            .title("OpenCraft Profile")
                            .description("Updated node profile.")
                            .addField("profile", config.profile, true)
                            .addField("canCoordinateFleet", String.valueOf(agentNetworkService.canCoordinateFleet()), true)
                            .addField("canAcceptFleetTasks", String.valueOf(agentNetworkService.canAcceptFleetTasks()), true)
                            .addField("Persistence", "Saved to plugins/config/opencraft.json", false);
                        return OK;
                    })))
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
                .then(literal("env").executes(c -> {
                    final String envVarName = config.apiKeyEnvVar == null ? "" : config.apiKeyEnvVar.strip();
                    c.getSource().getEmbed()
                        .title("OpenCraft Env Debug")
                        .addField("apiKeyEnvVar", envVarName.isBlank() ? "(blank)" : envVarName, false)
                        .addField("apiKeyEnvVarStatus", envVarName.isBlank() ? "blank in config" : "configured", true)
                        .addField("providerName", config.providerName, true)
                        .addField("providerBaseUrl", config.providerBaseUrl, false)
                        .addField("model", config.model, true);
                    return OK;
                }))
                .then(literal("whisper")
                    .then(argument("username", StringArgumentType.word())
                        .then(argument("message", StringArgumentType.greedyString())
                            .executes(c -> {
                                final String username = StringArgumentType.getString(c, "username").strip();
                                final String message = StringArgumentType.getString(c, "message").strip();
                                final var client = Proxy.getInstance().getClient();
                                final boolean clientConnected = client != null && client.isConnected();
                                final String whisperCommand = com.zenith.Globals.CONFIG.client.extra.whisperCommand;
                                if (!chatHandler.debugWhisper(username, message)) {
                                    c.getSource().getEmbed()
                                        .title("OpenCraft Whisper Debug")
                                        .description("Could not queue a test whisper for that username or message.")
                                        .errorColor();
                                    return ERROR;
                                }
                                c.getSource().getEmbed()
                                    .title("OpenCraft Whisper Debug")
                                    .description("Queued a test whisper. Run `/llm debug recent` to inspect the outbound event.")
                                    .addField("target", username, true)
                                    .addField("clientConnected", String.valueOf(clientConnected), true)
                                    .addField("whisperCommand", whisperCommand, true);
                                return OK;
                            }))))
            )
            .then(literal("agent")
                .then(literal("status").executes(c -> {
                    final AgentNetworkStatus status = agentNetworkService.status();
                    c.getSource().getEmbed()
                        .title("OpenCraft Agent Status")
                        .description(renderAgentPeers())
                        .addField("enabled", String.valueOf(status.enabled()), true)
                        .addField("configured", String.valueOf(status.configured()), true)
                        .addField("profile", status.profile(), true)
                        .addField("nodeId", blankAs(status.nodeId(), "(unset)"), true)
                        .addField("cluster", status.cluster(), true)
                        .addField("bind", status.bindHost() + ":" + status.port(), false)
                        .addField("sharedSecretEnvVar", status.sharedSecretEnvVar(), false)
                        .addField("secretPresent", String.valueOf(status.sharedSecretPresent()), true)
                        .addField("fingerprint", blankAs(agentNetworkService.challengeFingerprint(), "(missing)"), true)
                        .addField("peers", String.valueOf(status.configuredPeers()), true)
                        .addField("actionablePeers", String.valueOf(status.actionablePeers()), true)
                        .addField("sharedBilling", String.valueOf(status.sharedBillingAcrossPeers()), true)
                        .addField("canCoordinateFleet", String.valueOf(agentNetworkService.canCoordinateFleet()), true)
                        .addField("canAcceptFleetTasks", String.valueOf(agentNetworkService.canAcceptFleetTasks()), true);
                    return OK;
                }))
                .then(literal("run")
                    .then(literal("list").executes(c -> {
                        c.getSource().getEmbed()
                            .title("OpenCraft Fleet Runs")
                            .description(renderFleetRunList())
                            .addField("profile", config.profile, true)
                            .addField("canCoordinate", String.valueOf(agentNetworkService.canCoordinateFleet()), true)
                            .addField("canAccept", String.valueOf(agentNetworkService.canAcceptFleetTasks()), true);
                        return OK;
                    }))
                    .then(literal("show")
                        .then(argument("runId", StringArgumentType.word())
                            .executes(c -> {
                                final String runId = StringArgumentType.getString(c, "runId").strip();
                                final Optional<FleetRunSnapshot> run = agentNetworkService.fleetRun(runId);
                                if (run.isEmpty()) {
                                    c.getSource().getEmbed()
                                        .title("OpenCraft Fleet Run")
                                        .description("No fleet run found for: " + runId)
                                        .errorColor();
                                    return ERROR;
                                }
                                final FleetRunSnapshot snapshot = run.get();
                                c.getSource().getEmbed()
                                    .title("OpenCraft Fleet Run")
                                    .description(renderFleetRun(snapshot))
                                    .addField("runId", snapshot.runId(), false)
                                    .addField("status", snapshot.status().configValue(), true)
                                    .addField("profile", snapshot.profile(), true)
                                    .addField("billableUnits", String.valueOf(snapshot.billableRequestUnits()), true)
                                    .addField("sharedBilling", String.valueOf(snapshot.sharedBillingEnabled()), true);
                                return OK;
                            })))
                    .then(literal("create")
                        .then(argument("spec", StringArgumentType.greedyString())
                            .executes(c -> {
                                final String spec = StringArgumentType.getString(c, "spec");
                                final String[] split = spec.split("\\s+--\\s+", 2);
                                if (split.length != 2 || split[0].isBlank() || split[1].isBlank()) {
                                    c.getSource().getEmbed()
                                        .title("OpenCraft Fleet Run")
                                        .description("Use: /llm agent run create SUMMARY -- PEER_IDS")
                                        .errorColor();
                                    return ERROR;
                                }
                                final List<String> peers = parsePeerList(split[1]);
                                final Optional<FleetRunSnapshot> run = agentNetworkService.createFleetRun(
                                    commandSourceName(c),
                                    split[0].strip(),
                                    peers
                                );
                                if (run.isEmpty()) {
                                    c.getSource().getEmbed()
                                        .title("OpenCraft Fleet Run")
                                        .description("Could not create fleet run. This node profile may not coordinate fleet work.")
                                        .errorColor();
                                    return ERROR;
                                }
                                final FleetRunSnapshot snapshot = run.get();
                                c.getSource().getEmbed()
                                    .title("OpenCraft Fleet Run Created")
                                    .description(renderFleetRun(snapshot))
                                    .addField("runId", snapshot.runId(), false)
                                    .addField("targets", String.valueOf(snapshot.targetPeerIds().size()), true)
                                    .addField("billableUnits", String.valueOf(snapshot.billableRequestUnits()), true);
                                return OK;
                            })))
                    .then(literal("start")
                        .then(argument("runId", StringArgumentType.word())
                            .executes(brigadierCommand(c -> mutateRunResponse(
                                c,
                                "OpenCraft Fleet Run Started",
                                agentNetworkService.startFleetRun(StringArgumentType.getString(c, "runId").strip())
                            )))))
                    .then(literal("finish")
                        .then(argument("runId", StringArgumentType.word())
                            .executes(brigadierCommand(c -> mutateRunResponse(
                                c,
                                "OpenCraft Fleet Run Completed",
                                agentNetworkService.completeFleetRun(StringArgumentType.getString(c, "runId").strip(), "")
                            )))
                            .then(argument("detail", StringArgumentType.greedyString())
                                .executes(brigadierCommand(c -> mutateRunResponse(
                                    c,
                                    "OpenCraft Fleet Run Completed",
                                    agentNetworkService.completeFleetRun(
                                        StringArgumentType.getString(c, "runId").strip(),
                                        StringArgumentType.getString(c, "detail").strip()
                                    )
                                ))))))
                    .then(literal("fail")
                        .then(argument("runId", StringArgumentType.word())
                            .then(argument("reason", StringArgumentType.greedyString())
                                .executes(brigadierCommand(c -> mutateRunResponse(
                                    c,
                                    "OpenCraft Fleet Run Failed",
                                    agentNetworkService.failFleetRun(
                                        StringArgumentType.getString(c, "runId").strip(),
                                        StringArgumentType.getString(c, "reason").strip()
                                    )
                                ))))))
                    .then(literal("cancel")
                        .then(argument("runId", StringArgumentType.word())
                            .executes(brigadierCommand(c -> mutateRunResponse(
                                c,
                                "OpenCraft Fleet Run Cancelled",
                                agentNetworkService.cancelFleetRun(StringArgumentType.getString(c, "runId").strip(), "")
                            )))
                            .then(argument("detail", StringArgumentType.greedyString())
                                .executes(brigadierCommand(c -> mutateRunResponse(
                                    c,
                                    "OpenCraft Fleet Run Cancelled",
                                    agentNetworkService.cancelFleetRun(
                                        StringArgumentType.getString(c, "runId").strip(),
                                        StringArgumentType.getString(c, "detail").strip()
                                    )
                                ))))))
                    .then(literal("step")
                        .then(argument("runId", StringArgumentType.word())
                            .then(argument("peerId", StringArgumentType.word())
                                .then(argument("commandId", StringArgumentType.word())
                                    .then(argument("summary", StringArgumentType.greedyString())
                                        .executes(brigadierCommand(c -> mutateRunResponse(
                                            c,
                                            "OpenCraft Fleet Step Added",
                                            agentNetworkService.addFleetStep(
                                                StringArgumentType.getString(c, "runId").strip(),
                                                StringArgumentType.getString(c, "peerId").strip(),
                                                StringArgumentType.getString(c, "commandId").strip(),
                                                StringArgumentType.getString(c, "summary").strip()
                                            )
                                        ))))))))
                    .then(literal("step-status")
                        .then(argument("runId", StringArgumentType.word())
                            .then(argument("stepId", StringArgumentType.word())
                                .then(argument("status", enumStrings(
                                    "planned", "dispatched", "running", "completed", "failed", "cancelled"
                                ))
                                    .then(argument("detail", StringArgumentType.greedyString())
                                        .executes(brigadierCommand(c -> mutateRunResponse(
                                            c,
                                            "OpenCraft Fleet Step Updated",
                                            agentNetworkService.updateFleetStepStatus(
                                                StringArgumentType.getString(c, "runId").strip(),
                                                StringArgumentType.getString(c, "stepId").strip(),
                                                FleetStepStatus.fromString(StringArgumentType.getString(c, "status")),
                                                StringArgumentType.getString(c, "detail").strip()
                                            )
                                        )))))))))
                .then(literal("challenge")
                    .then(argument("peerId", StringArgumentType.word())
                        .executes(c -> {
                            final String peerId = StringArgumentType.getString(c, "peerId").strip();
                            final Optional<AgentChallenge> challenge = agentNetworkService.issueOnboardingChallenge(
                                peerId,
                                List.of("handshake", "execute")
                            );
                            if (challenge.isEmpty()) {
                                c.getSource().getEmbed()
                                    .title("OpenCraft Agent Challenge")
                                    .description("Could not issue challenge. Check agent mode, nodeId, peer config, and shared secret env var.")
                                    .errorColor();
                                return ERROR;
                            }
                            final AgentChallenge value = challenge.get();
                            c.getSource().getEmbed()
                                .title("OpenCraft Agent Challenge")
                                .addField("peerId", value.candidatePeerId(), true)
                                .addField("challengeId", value.challengeId(), false)
                                .addField("phrase", value.phrase(), false)
                                .addField("expiresAt", value.expiresAt().toString(), true)
                                .addField("ttlSeconds", String.valueOf(value.ttlSecondsRemaining(java.time.Clock.systemUTC())), true)
                                .addField("scopes", String.join(", ", value.scopes()), false);
                            return OK;
                        })))
                .then(literal("billing")
                    .then(argument("targetCount", IntegerArgumentType.integer(1))
                        .executes(c -> {
                            final int targetCount = IntegerArgumentType.getInteger(c, "targetCount");
                            final FleetTaskEnvelope envelope = agentNetworkService.draftFleetTask(
                                "management-preview",
                                java.util.stream.IntStream.rangeClosed(1, targetCount)
                                    .mapToObj(index -> "peer-" + index)
                                    .toList()
                            );
                            c.getSource().getEmbed()
                                .title("OpenCraft Agent Billing")
                                .description("Draft fan-out preview for a single user request.")
                                .addField("targetCount", String.valueOf(targetCount), true)
                                .addField("dispatches", String.valueOf(envelope.downstreamDispatchCount()), true)
                                .addField("billableRequestUnits", String.valueOf(envelope.billableRequestUnits()), true)
                                .addField("sharedBillingEnabled", String.valueOf(envelope.sharedBillingEnabled()), true)
                                .addField("fleetRequestId", envelope.fleetRequestId(), false);
                            return OK;
                        }))))
            .then(literal("config").executes(c -> {
                final AgentNetworkStatus agentStatus = agentNetworkService.status();
                c.getSource().getEmbed()
                    .title("OpenCraft Config")
                    .addField("prefix",              config.prefix,                                     true)
                    .addField("publicChatEnabled",   String.valueOf(config.publicChatEnabled),          true)
                    .addField("whisperEnabled",      String.valueOf(config.whisperEnabled),             true)
                    .addField("profile",             config.profile,                                    true)
                    .addField("model",               config.model,                                      true)
                    .addField("providerName",        config.providerName,                               true)
                    .addField("providerBaseUrl",     config.providerBaseUrl,                            false)
                    .addField("apiKeyEnvVar",        config.apiKeyEnvVar + " (value hidden)",           false)
                    .addField("userCooldownMs",      String.valueOf(config.userCooldownMs),             true)
                    .addField("userHourlyLimit",     String.valueOf(config.userHourlyLimit),            true)
                    .addField("allowedCommands",     config.allowedCommands.size() + " entries",        true)
                    .addField("discordAuditEnabled", String.valueOf(config.discordAuditEnabled),        true)
                    .addField("discordDebugEnabled", String.valueOf(config.discordDebugEnabled),        true)
                    .addField("users",               config.users.size() + " entries",                  true)
                    .addField("agent.enabled",       String.valueOf(config.agent.enabled),              true)
                    .addField("agent.nodeId",        blankAs(config.agent.nodeId, "(unset)"),          true)
                    .addField("agent.cluster",       config.agent.cluster,                              true)
                    .addField("agent.port",          String.valueOf(config.agent.port),                 true)
                    .addField("agent.peers",         String.valueOf(agentStatus.configuredPeers()),     true)
                    .addField("agent.maxRuns",       String.valueOf(config.agent.maxRetainedRuns),     true)
                    .addField("agent.canCoordinate", String.valueOf(agentNetworkService.canCoordinateFleet()), true)
                    .addField("agent.canAccept",     String.valueOf(agentNetworkService.canAcceptFleetTasks()), true)
                    .addField("agent.sharedBilling", String.valueOf(config.agent.shareBillingAcrossPeers), true);
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

    private String renderAgentPeers() {
        final List<com.zenith.plugin.opencraft.agent.AgentPeer> peers = agentNetworkService.peers();
        if (peers.isEmpty()) {
            return "No agent peers configured.";
        }
        final StringBuilder builder = new StringBuilder();
        for (final com.zenith.plugin.opencraft.agent.AgentPeer peer : peers) {
            if (builder.length() > 0) builder.append('\n');
            builder.append(peer.peerId())
                .append(" -> ")
                .append(peer.endpoint())
                .append(" / role=")
                .append(blankAs(peer.role(), "worker"))
                .append(" / enabled=")
                .append(peer.enabled())
                .append(" / execute=")
                .append(peer.allowTaskExecution());
        }
        return builder.toString();
    }

    private String renderFleetRunList() {
        final List<FleetRunSnapshot> runs = agentNetworkService.recentFleetRuns(10);
        if (runs.isEmpty()) {
            return "No fleet runs recorded.";
        }
        final StringBuilder builder = new StringBuilder();
        for (final FleetRunSnapshot run : runs) {
            if (builder.length() > 0) builder.append('\n');
            builder.append(run.compactSummary())
                .append(" / ")
                .append(run.requestSummary());
        }
        return builder.toString();
    }

    private String renderFleetRun(final FleetRunSnapshot run) {
        final StringBuilder builder = new StringBuilder();
        builder.append(run.requestSummary())
            .append("\nTargets: ")
            .append(String.join(", ", run.targetPeerIds()))
            .append("\nSteps:");
        if (run.steps().isEmpty()) {
            builder.append(" none");
        } else {
            for (final FleetStepSnapshot step : run.steps()) {
                builder.append("\n- ").append(step.compactSummary());
                if (step.detail() != null && !step.detail().isBlank()) {
                    builder.append(" / ").append(step.detail());
                }
            }
        }
        builder.append("\nRecent events:");
        final List<com.zenith.plugin.opencraft.agent.FleetRunEvent> events = run.events();
        if (events.isEmpty()) {
            builder.append(" none");
        } else {
            final int start = Math.max(0, events.size() - 5);
            for (int index = start; index < events.size(); index++) {
                final com.zenith.plugin.opencraft.agent.FleetRunEvent event = events.get(index);
                builder.append("\n- ").append(event.type()).append(": ").append(event.message());
            }
        }
        return builder.toString();
    }

    private int mutateRunResponse(final com.mojang.brigadier.context.CommandContext<CommandContext> context,
                                  final String title,
                                  final Optional<FleetRunSnapshot> runOpt) {
        if (runOpt.isEmpty()) {
            context.getSource().getEmbed()
                .title(title)
                .description("Unable to update fleet run.")
                .errorColor();
            return ERROR;
        }
        final FleetRunSnapshot run = runOpt.get();
        context.getSource().getEmbed()
            .title(title)
            .description(renderFleetRun(run))
            .addField("runId", run.runId(), false)
            .addField("status", run.status().configValue(), true)
            .addField("steps", String.valueOf(run.steps().size()), true);
        return OK;
    }

    private static com.mojang.brigadier.Command<CommandContext> brigadierCommand(
        final ToIntFunction<com.mojang.brigadier.context.CommandContext<CommandContext>> handler
    ) {
        return handler::applyAsInt;
    }

    private static String commandSourceName(final com.mojang.brigadier.context.CommandContext<CommandContext> context) {
        if (context == null || context.getSource() == null || context.getSource().getSource() == null) {
            return "unknown";
        }
        return context.getSource().getSource().name();
    }

    private static List<String> parsePeerList(final String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return java.util.Arrays.stream(raw.split("[,\\s]+"))
            .map(String::strip)
            .filter(value -> !value.isBlank())
            .distinct()
            .toList();
    }

    private static String blankAs(final String value, final String fallback) {
        return value == null || value.isBlank() ? fallback : value;
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
