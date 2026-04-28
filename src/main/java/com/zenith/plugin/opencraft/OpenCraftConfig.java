package com.zenith.plugin.opencraft;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Persistent configuration serialized and managed by PluginAPI.registerConfig().
 *
 * SECURITY CONTRACT:
 *   - This class must NEVER store raw API keys, webhook tokens, or passwords.
 *   - Fields ending in "EnvVar" store only the NAME of an environment variable.
 *   - Secrets are resolved at runtime via System.getenv(). They are never
 *     logged, sent to Discord, sent to Minecraft chat, or injected into LLM prompts.
 */
public class OpenCraftConfig {

    // ── Chat trigger ──────────────────────────────────────────────────────────
    /** In-game prefix that triggers the LLM (e.g., "!gpt Hello"). */
    public String prefix = "!gpt";
    /** Allow triggers from public chat. Disabled by default to reduce blast radius. */
    public boolean publicChatEnabled = false;
    /** Allow triggers from whisper/tell messages. */
    public boolean whisperEnabled = true;
    /**
     * Prefix prepended to every LLM response whispered back to the user.
     * Must not impersonate server or admin messages.
     */
    public String responsePrefix = "[OC]";
    /** Maximum characters per whisper chunk (Minecraft limit is 256; keep below 200 for safety). */
    public int whisperChunkSize = 190;
    /** Configurable regex pattern used to detect incoming whispers in system chat.
     *  Default matches vanilla format: "<username> whispers to you: <message>" */
    public String whisperInboundPattern = "^(\\S+) whispers to you: (.+)$";

    // ── LLM Provider ─────────────────────────────────────────────────────────
    /** Logical provider name used in logs and Discord notifications. */
    public String providerName = "openai";
    /** Base URL for the OpenAI-compatible API. Change for local/self-hosted endpoints. */
    public String providerBaseUrl = "https://api.openai.com/v1";
    /** Model identifier passed to the API. */
    public String model = "gpt-4o";
    /**
     * Name of the environment variable that holds the API key.
     * The actual key is NEVER stored here. Default: OPENCRAFT_API_KEY.
     */
    public String apiKeyEnvVar = "OPENCRAFT_API_KEY";
    /** Request timeout in seconds. */
    public int timeoutSeconds = 30;
    /** Maximum characters accepted from user input before truncation. */
    public int maxInputLength = 1000;
    /** Maximum output tokens requested from the model. */
    public int maxOutputTokens = 500;
    /** Sampling temperature (0.0–2.0). Lower = more deterministic. */
    public double temperature = 0.7;

    // ── Retry policy ──────────────────────────────────────────────────────────
    /** Maximum number of retries on transient provider errors or rate limits. */
    public int maxRetries = 3;
    /** Base delay in milliseconds between retries (multiplied by attempt number). */
    public long retryDelayMs = 1000;

    // ── Rate limits ───────────────────────────────────────────────────────────
    /** Minimum milliseconds between requests from the same user. */
    public long userCooldownMs = 5000;
    /** Maximum requests per user per hour (0 = unlimited). */
    public int userHourlyLimit = 30;
    /** Maximum global LLM requests per minute across all users. */
    public int globalRequestsPerMinute = 60;
    /** Maximum simultaneous in-flight LLM requests. */
    public int maxConcurrentRequests = 5;
    /** Daily token budget guard (0 = disabled). Resets at UTC midnight. */
    public int dailyBudgetTokens = 250_000;

    // ── Identity ──────────────────────────────────────────────────────────────
    /**
     * When true, allows username-only lookups if a UUID cannot be confirmed.
     * SECURITY WARNING: Username-based trust is weaker than UUID-based trust.
     * Enable only on offline-mode servers where UUIDs are unavailable.
     * Admin role is never granted through username-only resolution.
     */
    public boolean allowUsernameOnlyFallback = false;

    // ── RBAC ──────────────────────────────────────────────────────────────────
    /**
     * Maps Minecraft UUID (string, with or without dashes) to role.
     * Supported roles: "member", "admin".
     * Example: {"069a79f4-44e9-4726-a5be-fca90e38aaf5": "admin"}
     * Usernames may also be used as keys when allowUsernameOnlyFallback=true,
     * but admin role is never granted through username-only lookup.
     */
    public Map<String, String> users = new HashMap<>();

    // ── Admin command allowlist ───────────────────────────────────────────────
    /** Deny-by-default. Only commands listed here may ever be executed. */
    public List<AllowedCommandConfig> allowedCommands = new ArrayList<>();

    // ── Discord audit notifications ───────────────────────────────────────────
    /**
     * Name of the environment variable holding the Discord webhook URL.
     * The URL itself must NEVER be stored in this config or any committed file.
     */
    public String discordWebhookEnvVar = "OPENCRAFT_DISCORD_WEBHOOK";
    public boolean discordAuditEnabled = false;
    /** Log unauthorized access attempts to Discord. */
    public boolean discordLogDenied = true;
    /** Log admin command intents and execution results to Discord. */
    public boolean discordLogAdminCommands = true;

    // ── Local audit logging ───────────────────────────────────────────────────
    public boolean auditLogEnabled = true;
    public String auditLogPath = "logs/opencraft-audit.log";
    public int auditRetentionDays = 30;
    public boolean logDeniedAttempts = true;

    // ── System prompt ─────────────────────────────────────────────────────────
    /**
     * Optional operator override for the base system prompt body.
     * The runtime context block and mandatory security rules are always
     * prepended by PromptBuilder regardless of this setting.
     * Leave blank to use the built-in hardened prompt.
     */
    public String systemPromptOverride = "";

    // ── Confirmation ─────────────────────────────────────────────────────────
    /** Seconds an admin has to confirm a high-risk command before it expires. */
    public int confirmationTimeoutSeconds = 60;

    // ── Runtime context ───────────────────────────────────────────────────────
    /** IANA timezone injected into every LLM request as grounding context. */
    public String timezone = "UTC";

    // ── Updates ───────────────────────────────────────────────────────────────
    public boolean updateCheckOnLoad = true;
    public boolean updateAutoDownload = false;
    /** Release channel: "stable", "beta", or "dev". */
    public String updateChannel = "stable";

    // ── Operations (Observe/Plan/Execute cycle) ───────────────────────────────
    /**
     * Enable multi-step operational planning. When false, the LLM may only
     * return "response", "command_intent", "refusal", and "clarification" types.
     * When true, the "plan" type is also enabled and the operational cycle
     * instructions are injected into the admin system prompt.
     * Disabled by default — enable only after reviewing baseline operations.
     */
    public boolean operationsEnabled = false;
    /**
     * Merge the built-in baseline ZenithProxy operations (pathfinder.*, status.query,
     * tasks.*) into the allowlist automatically. Requires operationsEnabled=true.
     * Operators should review BaselineOperations.java before enabling.
     */
    public boolean baselineOperationsEnabled = false;
    /**
     * Seconds the admin has to confirm a staged operation before it expires.
     * Applies in addition to confirmationTimeoutSeconds (single-command confirmation).
     */
    public int operationConfirmationTimeoutSeconds = 120;
    /**
     * Estimated token cost above which the plan summary includes a cost warning
     * and confirmation is recommended. 0 = disabled.
     */
    public int operationCostWarnThreshold = 2000;
    /** Maximum steps permitted in a single plan. Prevents runaway multi-step ops. */
    public int maxOperationSteps = 10;
    /**
     * Minutes to wait for a pathfinder step to complete before timing out.
     * Applies per-step, not per-operation.
     */
    public int operationStepTimeoutMinutes = 10;

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * A single entry in the admin command allowlist.
     * The allowlist is deny-by-default; only commands defined here may be
     * executed, and only by users with the required role.
     */
    public static class AllowedCommandConfig {
        /** Unique, stable identifier. The LLM must produce this exact string. */
        public String commandId = "";
        /**
         * Human-readable description exposed to the LLM in the admin system prompt.
         * Must not reveal sensitive operational details.
         */
        public String description = "";
        /**
         * The ZenithProxy command string to execute (e.g., "stash scan").
         * NOT exposed to the LLM; Java-side only.
         */
        public String zenithCommand = "";
        /** Minimum role required. Currently only "admin" is meaningful. */
        public String roleRequired = "admin";
        /** "low", "medium", or "high". High-risk commands require confirmation. */
        public String riskLevel = "low";
        /** If true, the admin must explicitly confirm before execution. */
        public boolean confirmationRequired = false;
        /**
         * Field names whose values should be redacted from any output before
         * logging, whispering, or sending to Discord.
         */
        public List<String> redactFields = new ArrayList<>();
        /**
         * Optional JSON schema for validating LLM-supplied arguments.
         * If empty, no arguments are accepted for this command.
         */
        public Map<String, String> argumentSchema = new HashMap<>();
    }
}
