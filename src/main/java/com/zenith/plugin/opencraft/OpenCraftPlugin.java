package com.zenith.plugin.opencraft;

import com.zenith.plugin.api.Plugin;
import com.zenith.plugin.api.PluginAPI;
import com.zenith.plugin.api.ZenithProxyPlugin;
import com.zenith.plugin.opencraft.audit.AuditLogger;
import com.zenith.plugin.opencraft.auth.AuthorizationService;
import com.zenith.plugin.opencraft.chat.ChatHandler;
import com.zenith.plugin.opencraft.command.OpenCraftCommand;
import com.zenith.plugin.opencraft.debug.ChatDebugRecorder;
import com.zenith.plugin.opencraft.discord.DiscordNotifier;
import com.zenith.plugin.opencraft.execute.OperationExecutor;
import com.zenith.plugin.opencraft.intent.CommandAllowlist;
import com.zenith.plugin.opencraft.intent.CommandExecutor;
import com.zenith.plugin.opencraft.prompt.PromptBuilder;
import com.zenith.plugin.opencraft.provider.OpenCraftProvider;
import com.zenith.plugin.opencraft.provider.ProviderFactory;
import com.zenith.plugin.opencraft.ratelimit.RateLimiter;
import com.zenith.plugin.opencraft.update.PluginUpdateService;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

@Plugin(
    id          = BuildConstants.PLUGIN_ID,
    version     = BuildConstants.VERSION,
    description = "Secure LLM assistant for ZenithProxy: member Q&A and admin natural-language command execution",
    url         = "https://github.com/poseidonscave/opencraft",
    authors     = {"PoseidonsCave"},
    mcVersions  = {BuildConstants.MC_VERSION}
)
public class OpenCraftPlugin implements ZenithProxyPlugin {

    private static ComponentLogger      logger;
    private static OpenCraftConfig            config;
    private static OpenCraftModule            module;
    private static PluginUpdateService  updateService;
    private static AuditLogger          auditLogger;
    private static DiscordNotifier      discordNotifier;
    private static OpenCraftProvider         provider;
    private static RateLimiter               rateLimiter;
    private static AuthorizationService      authService;
    private static CommandAllowlist          commandAllowlist;
    private static CommandExecutor           commandExecutor;
    private static OperationExecutor         operationExecutor;
    private static PromptBuilder             promptBuilder;
    private static ChatHandler               chatHandler;
    private static ChatDebugRecorder         chatDebugRecorder;

    @Override
    public void onLoad(final PluginAPI pluginAPI) {
        logger = pluginAPI.getLogger();
        config = pluginAPI.registerConfig(BuildConstants.PLUGIN_ID, OpenCraftConfig.class);

        validateConfig(config);

        auditLogger       = new AuditLogger(config, logger);
        discordNotifier   = new DiscordNotifier(config, logger);
        provider          = ProviderFactory.create(config, logger);
        rateLimiter       = new RateLimiter(config);
        authService       = new AuthorizationService(config, logger);
        commandAllowlist  = new CommandAllowlist(config);
        commandExecutor   = new CommandExecutor(config, commandAllowlist, auditLogger, discordNotifier, logger);
        operationExecutor = new OperationExecutor(config, commandExecutor, auditLogger, logger);
        promptBuilder     = new PromptBuilder(config, commandAllowlist);
        chatDebugRecorder = new ChatDebugRecorder();

        chatHandler = new ChatHandler(
            config, authService, rateLimiter, provider,
            promptBuilder, commandExecutor, operationExecutor, auditLogger, discordNotifier, logger, chatDebugRecorder
        );

        module        = new OpenCraftModule(config, chatHandler, logger);
        updateService = new PluginUpdateService(config, logger);

        pluginAPI.registerModule(module);
        pluginAPI.registerCommand(new OpenCraftCommand(
            config, module, updateService, auditLogger, logger, chatDebugRecorder
        ));

        updateService.scheduleStartupCheck();

        logger.info("[OpenCraft] Loaded v{} — provider: {}, public chat: {}, whisper: {}",
            BuildConstants.VERSION, config.providerName,
            config.publicChatEnabled, config.whisperEnabled);
    }

    private static void validateConfig(final OpenCraftConfig cfg) {
        if (cfg.prefix == null || cfg.prefix.isBlank()) {
            logger.warn("[OpenCraft] Config: 'prefix' was blank; falling back to '!oc'.");
            cfg.prefix = "!oc";
        } else {
            cfg.prefix = cfg.prefix.strip();
        }
        if (cfg.apiKeyEnvVar == null || cfg.apiKeyEnvVar.isBlank()) {
            logger.warn("[OpenCraft] Config: 'apiKeyEnvVar' was blank; falling back to 'OPENCRAFT_API_KEY'.");
            cfg.apiKeyEnvVar = "OPENCRAFT_API_KEY";
        } else {
            cfg.apiKeyEnvVar = cfg.apiKeyEnvVar.strip();
        }
        if (cfg.whisperChunkSize < 10 || cfg.whisperChunkSize > 200) {
            final int clamped = Math.max(10, Math.min(200, cfg.whisperChunkSize));
            logger.warn("[OpenCraft] Config: 'whisperChunkSize'={} out of range [10,200]; clamping to {}.",
                cfg.whisperChunkSize, clamped);
            cfg.whisperChunkSize = clamped;
        }
        if (cfg.maxConcurrentRequests < 1) {
            logger.warn("[OpenCraft] Config: 'maxConcurrentRequests'={} < 1; clamping to 1.",
                cfg.maxConcurrentRequests);
            cfg.maxConcurrentRequests = 1;
        }
        if (cfg.timeoutSeconds < 1) {
            logger.warn("[OpenCraft] Config: 'timeoutSeconds'={} < 1; clamping to 30.", cfg.timeoutSeconds);
            cfg.timeoutSeconds = 30;
        }
        final String apiKey = System.getenv(cfg.apiKeyEnvVar);
        if (apiKey == null || apiKey.isBlank()) {
            logger.warn("[OpenCraft] WARNING: Environment variable '{}' is not set or is blank. " +
                "The plugin will fail on the first LLM request.", cfg.apiKeyEnvVar);
        }
    }

    public static ComponentLogger      getLogger()          { return logger; }
    public static OpenCraftConfig            getConfig()          { return config; }
    public static OpenCraftModule            getModule()          { return module; }
    public static PluginUpdateService  getUpdateService()   { return updateService; }
    public static AuditLogger          getAuditLogger()     { return auditLogger; }
    public static DiscordNotifier      getDiscordNotifier() { return discordNotifier; }
}
