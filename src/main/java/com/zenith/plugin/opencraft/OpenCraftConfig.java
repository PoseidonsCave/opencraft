package com.zenith.plugin.opencraft;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class OpenCraftConfig {
        public String prefix = "!oc";
        public boolean publicChatEnabled = false;
        public boolean whisperEnabled = true;
        public String responsePrefix = "[OC]";
        public int whisperChunkSize = 190;
        public String whisperInboundPattern = "^(\\S+) whispers to you: (.+)$";
        public String providerName = "openai";
        public String providerBaseUrl = "https://api.openai.com/v1";
        public String model = "gpt-4o";
        public String apiKeyEnvVar = "OPENCRAFT_API_KEY";
        public int timeoutSeconds = 30;
        public int maxInputLength = 1000;
        public int maxOutputTokens = 500;
        public double temperature = 0.7;
        public int maxRetries = 3;
        public long retryDelayMs = 1000;
        public long userCooldownMs = 5000;
        public int userHourlyLimit = 30;
        public int globalRequestsPerMinute = 60;
        public int maxConcurrentRequests = 5;
        public int dailyBudgetTokens = 250_000;
        public boolean allowUsernameOnlyFallback = false;
        public Map<String, String> users = new HashMap<>();
        public List<AllowedCommandConfig> allowedCommands = new ArrayList<>();
        public String discordWebhookEnvVar = "OPENCRAFT_DISCORD_WEBHOOK";
    public boolean discordAuditEnabled = false;
        public boolean discordLogDenied = true;
        public boolean discordLogAdminCommands = true;
        public boolean discordDebugEnabled = false;
    public boolean auditLogEnabled = true;
    public String auditLogPath = "logs/opencraft-audit.log";
    public int auditRetentionDays = 30;
        public boolean logDeniedAttempts = true;
        public String systemPromptOverride = "";
        public int confirmationTimeoutSeconds = 60;
        public String timezone = "UTC";
        public String profile = "manager";
    public boolean updateCheckOnLoad = true;
    public boolean updateAutoDownload = false;
        public String updateChannel = "stable";
        public boolean operationsEnabled = false;
        public boolean baselineOperationsEnabled = true;
        public int operationConfirmationTimeoutSeconds = 120;
        public int operationCostWarnThreshold = 2000;
        public int maxOperationSteps = 10;
        public int operationStepTimeoutMinutes = 10;
        public final AgentConfig agent = new AgentConfig();

        public static class AgentConfig {
                public boolean enabled = false;
                public String nodeId = "";
                public String cluster = "default";
                public String bindHost = "0.0.0.0";
                public int port = 38265;
                public String sharedSecretEnvVar = "OPENCRAFT_AGENT_SECRET";
        public int challengeTtlSeconds = 120;
        public int allowedClockSkewSeconds = 30;
        public boolean shareBillingAcrossPeers = true;
        public int maxRetainedRuns = 100;
        public List<AgentPeerConfig> peers = new ArrayList<>();
        }

        public static class AgentPeerConfig {
                public String peerId = "";
                public String displayName = "";
                public String host = "";
                public int port = 38265;
                public String role = "worker";
                public boolean enabled = true;
                public boolean allowTaskExecution = true;
        }

        public static class AllowedCommandConfig {
                public String commandId = "";
                public String description = "";
                public String zenithCommand = "";
                public String roleRequired = "admin";
                public String riskLevel = "low";
                public boolean confirmationRequired = false;
                public List<String> redactFields = new ArrayList<>();
                public Map<String, String> argumentSchema = new HashMap<>();
    }
}
