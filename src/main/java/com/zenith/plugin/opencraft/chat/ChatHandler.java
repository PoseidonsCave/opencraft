package com.zenith.plugin.opencraft.chat;

import com.zenith.Proxy;
import com.zenith.util.ChatUtil;
import com.zenith.plugin.opencraft.OpenCraftConfig;
import com.zenith.plugin.opencraft.audit.AuditEvent;
import com.zenith.plugin.opencraft.audit.AuditLogger;
import com.zenith.plugin.opencraft.auth.AuthorizationService;
import com.zenith.plugin.opencraft.auth.UserIdentity;
import com.zenith.plugin.opencraft.debug.ChatDebugRecorder;
import com.zenith.plugin.opencraft.discord.DiscordNotifier;
import com.zenith.plugin.opencraft.execute.OperationExecutor;
import com.zenith.plugin.opencraft.intent.CommandExecutor;
import com.zenith.plugin.opencraft.intent.ExecutionResult;
import com.zenith.plugin.opencraft.intent.IntentParser;
import com.zenith.plugin.opencraft.intent.IntentParser.ClarificationResponse;
import com.zenith.plugin.opencraft.intent.IntentParser.CommandIntentResponse;
import com.zenith.plugin.opencraft.intent.IntentParser.PlainResponse;
import com.zenith.plugin.opencraft.intent.IntentParser.PlanResponse;
import com.zenith.plugin.opencraft.intent.IntentParser.RefusalResponse;
import com.zenith.plugin.opencraft.observe.WorldState;
import com.zenith.plugin.opencraft.observe.WorldStateObserver;
import com.zenith.plugin.opencraft.plan.OperationalPlan;
import com.zenith.plugin.opencraft.prompt.PromptBuilder;
import com.zenith.plugin.opencraft.provider.OpenCraftProvider;
import com.zenith.plugin.opencraft.provider.OpenCraftProviderException;
import com.zenith.plugin.opencraft.provider.OpenCraftRequest;
import com.zenith.plugin.opencraft.provider.OpenCraftResponse;
import com.zenith.plugin.opencraft.ratelimit.RateLimiter;
import com.zenith.plugin.opencraft.ratelimit.RateLimitResult;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.jspecify.annotations.Nullable;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatPacket;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

public final class ChatHandler {

    private static final int MAX_USERNAME_LEN = 16;
    private static final Pattern SAFE_USERNAME = Pattern.compile("[a-zA-Z0-9_]{1,16}");

    private final OpenCraftConfig      config;
    private final AuthorizationService authService;
    private final RateLimiter          rateLimiter;
    private final OpenCraftProvider    provider;
    private final PromptBuilder        promptBuilder;
    private final CommandExecutor      commandExecutor;
    private final OperationExecutor    operationExecutor;
    private final AuditLogger          auditLogger;
    private final DiscordNotifier      discordNotifier;
    private final ComponentLogger      logger;
    private final IntentParser         intentParser;
    private final WorldStateObserver   worldStateObserver;
    private final WhisperPatternMatcher whisperPatternMatcher;
    private final ChatDebugRecorder    chatDebugRecorder;
    private final ExecutorService      llmExecutor;
    private final AtomicLong           reqCounter = new AtomicLong(0);

    public ChatHandler(final OpenCraftConfig config,
                       final AuthorizationService authService,
                       final RateLimiter rateLimiter,
                       final OpenCraftProvider provider,
                       final PromptBuilder promptBuilder,
                       final CommandExecutor commandExecutor,
                       final OperationExecutor operationExecutor,
                       final AuditLogger auditLogger,
                       final DiscordNotifier discordNotifier,
                       final ComponentLogger logger,
                       final ChatDebugRecorder chatDebugRecorder) {
        this.config             = config;
        this.authService        = authService;
        this.rateLimiter        = rateLimiter;
        this.provider           = provider;
        this.promptBuilder      = promptBuilder;
        this.commandExecutor    = commandExecutor;
        this.operationExecutor  = operationExecutor;
        this.auditLogger        = auditLogger;
        this.discordNotifier    = discordNotifier;
        this.logger             = logger;
        this.intentParser       = new IntentParser(logger);
        this.worldStateObserver = new WorldStateObserver(logger);
        this.whisperPatternMatcher = new WhisperPatternMatcher();
        this.chatDebugRecorder  = chatDebugRecorder;
        this.llmExecutor        = Executors.newCachedThreadPool(
            r -> { final Thread t = new Thread(r, "opencraft-worker"); t.setDaemon(true); return t; }
        );
    }

    private void debug(final String stage, final String detail) {
        debug("debug", stage, detail);
    }

    private void debug(final String requestId, final String stage, final String detail) {
        chatDebugRecorder.record(stage, detail);
        discordNotifier.notifyDebug(requestId, stage, detail);
    }

    public void onPlayerChat(final UUID senderUuid, final String rawMessage) {
        final String stripped = ChatContentUtil.stripColorCodes(rawMessage);
        if (!stripped.startsWith(config.prefix)) return;
        debug("public-chat.seen", stripped);
        if (!config.publicChatEnabled) {
            debug("public-chat.ignored", "publicChatEnabled=false");
            logger.debug("[OpenCraft] Ignored prefixed public chat because publicChatEnabled=false.");
            return;
        }

        final String userInput = stripped.substring(config.prefix.length()).strip();
        final String username = resolveUsername(senderUuid);
        handleRequest(senderUuid, username, userInput, "public_chat");
    }

    public void onSystemChat(final Object formattedContent) {
        if (!config.whisperEnabled) return;
        final String plain = ChatContentUtil.extractPlainText(formattedContent);
        if (plain == null) return;
        final WhisperPatternMatcher.WhisperMatch whisperMatch =
            whisperPatternMatcher.match(plain, config.whisperInboundPattern);
        if (whisperMatch == null) {
            if (plain.contains(config.prefix)) {
                debug("whisper.unmatched", plain);
                logger.debug("[OpenCraft] Ignored system chat containing prefix because it did not match whisperInboundPattern: {}",
                    plain);
            }
            return;
        }
        final String senderName = whisperMatch.senderName();
        final String rawMessage = whisperMatch.rawMessage();
        debug("whisper.matched", senderName + " -> " + rawMessage);

        if (!rawMessage.startsWith(config.prefix)) {
            debug("whisper.ignored", "missing prefix in message from " + senderName);
            return;
        }
        final String userInput = rawMessage.substring(config.prefix.length()).strip();
        final UUID uuid = resolveUuidByUsername(senderName);
        debug("whisper.dispatch", senderName + " uuid=" + (uuid != null ? uuid : "missing"));
        handleRequest(uuid, senderName, userInput, "whisper");
    }

    private void handleRequest(@Nullable final UUID uuid,
                                final String username,
                                final String userInput,
                                final String sourceType) {
        final String requestId = generateRequestId();
        debug(requestId, "request.received", "user=" + username + " source=" + sourceType);
        if (!isSafeUsername(username)) {
            debug(requestId, "request.rejected", "unsafe username");
            logger.debug("[OpenCraft] req={} Rejected message with unsafe username.", requestId);
            return;
        }
        final Optional<UserIdentity> identityOpt = authService.resolve(uuid, username);
        if (identityOpt.isPresent() && "confirm".equalsIgnoreCase(userInput)) {
            debug(requestId, "request.confirm", "confirm");
            handleConfirm(identityOpt.get(), username, requestId, sourceType);
            return;
        }
        if (identityOpt.isPresent() && "cancel".equalsIgnoreCase(userInput)) {
            debug(requestId, "request.cancel", "cancel");
            handleCancel(identityOpt.get(), username, requestId, sourceType);
            return;
        }
        if (identityOpt.isEmpty()) {
            debug(requestId, "auth.denied", "not whitelisted");
            if (config.logDeniedAttempts) {
                auditLogger.log(AuditEvent.requestDenied(requestId, username, "Not whitelisted"));
                discordNotifier.notifyDenied(requestId, null, "Not whitelisted");
            }
            return;
        }

        final UserIdentity identity = identityOpt.get();
        debug(requestId, "auth.allowed", "role=" + identity.role().name().toLowerCase());
        final String rateKey = uuid != null ? uuid.toString() : username;
        final RateLimitResult rateResult = rateLimiter.check(rateKey);
        if (!rateResult.allowed()) {
            debug(requestId, "rate-limited", rateResult.reason());
            auditLogger.log(AuditEvent.rateLimited(requestId, identity, rateResult.reason()));
            respond(username, rateResult.reason(), sourceType);
            return;
        }

        rateLimiter.recordRequest(rateKey);
        if (!rateLimiter.acquireConcurrencySlot()) {
            debug(requestId, "request.busy", "no concurrency slot");
            respond(username, "The assistant is busy. Please try again shortly.", sourceType);
            return;
        }

        debug(requestId, "request.dispatched", "queued for provider");
        llmExecutor.execute(() -> {
            try {
                processRequest(identity, userInput, sourceType, requestId);
            } finally {
                rateLimiter.releaseConcurrencySlot();
            }
        });
    }

    private void processRequest(final UserIdentity identity,
                                 final String userInput,
                                 final String sourceType,
                                 final String requestId) {
        debug(requestId, "provider.start", "model=" + config.model);
        auditLogger.log(AuditEvent.promptReceived(requestId, identity, userInput));
        discordNotifier.notifyPromptReceived(requestId, identity, userInput, sourceType);

        final WorldState worldState = worldStateObserver.observe();
        final String systemPrompt = promptBuilder.build(identity, requestId, worldState);
        final OpenCraftRequest req = new OpenCraftRequest(requestId, systemPrompt, userInput);

        final OpenCraftResponse response;
        try {
            response = provider.complete(req);
        } catch (final OpenCraftProviderException e) {
            debug(requestId, "provider.error", e.getMessage());
            logger.warn("[OpenCraft] req={} Provider error: {}", requestId, e.getMessage());
            auditLogger.log(AuditEvent.providerError(requestId, identity, e.getMessage()));
            discordNotifier.notifyProviderError(requestId, identity, e.getMessage());
            respond(identity.username(), "The assistant is temporarily unavailable.", sourceType);
            return;
        }

        debug(requestId, "provider.ok", "tokens=" + response.totalTokens());
        rateLimiter.recordTokens(response.totalTokens());

        final var parsed = intentParser.parse(response.rawContent(), requestId);

        if (parsed instanceof PlainResponse plain) {
            debug(requestId, "response.plain", plain.content());
            final String content = plain.content();
            auditLogger.log(AuditEvent.responseSent(requestId, identity, content, provider.name()));
            discordNotifier.notifyResponseSent(requestId, identity, content);
            chunkResponse(identity.username(), content, sourceType);
            return;
        }

        if (parsed instanceof CommandIntentResponse commandIntent) {
            debug(requestId, "response.command", commandIntent.intent().commandId());
            final ExecutionResult result =
                commandExecutor.execute(commandIntent.intent(), identity, requestId);
            respond(identity.username(), result.message(), sourceType);
            return;
        }

        if (parsed instanceof PlanResponse planResponse) {
            debug(requestId, "response.plan", "steps=" + planResponse.plan().steps().size());
            if (!config.operationsEnabled) {
                respond(identity.username(), "Operations are not enabled.", sourceType);
                return;
            }
            final OperationalPlan plan = planResponse.plan();
            final String costWarning = (config.operationCostWarnThreshold > 0
                && plan.costEstimate() > config.operationCostWarnThreshold)
                ? " (estimated token cost: " + plan.costEstimate() + ")" : "";
            if (plan.requiresApproval()) {
                operationExecutor.stagePlan(plan, identity);
                chunkResponse(identity.username(), "Plan: " + plan.summary()
                    + costWarning + " — Reply 'confirm' to proceed or 'cancel' to abort.", sourceType);
            } else {
                chunkResponse(identity.username(), "Executing: " + plan.summary()
                    + costWarning, sourceType);
                operationExecutor.startOperation(plan, identity, requestId,
                    msg -> respond(identity.username(), msg, sourceType));
            }
            auditLogger.log(AuditEvent.operationStarted(requestId, identity,
                plan.steps().size(), plan.risk()));
            return;
        }

        if (parsed instanceof RefusalResponse refusal) {
            debug(requestId, "response.refusal", refusal.reason());
            auditLogger.log(AuditEvent.requestDenied(requestId, identity.username(), refusal.reason()));
            chunkResponse(identity.username(), refusal.reason(), sourceType);
            return;
        }

        if (parsed instanceof ClarificationResponse clarification) {
            debug(requestId, "response.clarify", clarification.message());
            chunkResponse(identity.username(), clarification.message(), sourceType);
        }
    }

    private void handleConfirm(final UserIdentity identity, final String username,
                                 final String requestId, final String sourceType) {
        if (config.operationsEnabled && operationExecutor.hasStagedPlan(identity)) {
            final String result = operationExecutor.startStagedOperation(identity, requestId,
                msg -> respond(username, msg, sourceType));
            if (result != null) {
                respond(username, result, sourceType);
            }
            return;
        }
        if (config.operationsEnabled && operationExecutor.isAwaitingStepConfirmation(identity)) {
            final String result = operationExecutor.confirmStep(identity, requestId,
                msg -> respond(username, msg, sourceType));
            if (result != null) {
                respond(username, result, sourceType);
            }
            return;
        }
        if (!commandExecutor.hasPendingConfirmation(identity)) {
            respond(username, "No pending command to confirm.", sourceType);
            return;
        }
        final ExecutionResult result = commandExecutor.confirm(identity, requestId);
        respond(username, result.message(), sourceType);
    }

    private void handleCancel(final UserIdentity identity, final String username,
                                final String requestId, final String sourceType) {
        final boolean stagedCancelled = config.operationsEnabled && operationExecutor.clearStagedPlan(identity);
        final boolean operationCancelled = config.operationsEnabled && operationExecutor.cancelOperation(identity);
        final boolean cmdCancelled = commandExecutor.cancel(identity);
        if (stagedCancelled || operationCancelled || cmdCancelled) {
            respond(username, "Cancelled.", sourceType);
        } else {
            respond(username, "Nothing to cancel.", sourceType);
        }
    }

        private void chunkResponse(final String username, final String message, final String sourceType) {
        if (message == null || message.isBlank()) return;
        final String full = ensureResponsePrefix(message);
        final int chunkSize = config.whisperChunkSize;

        if (full.length() <= chunkSize) {
            sendChat(username, full, sourceType);
            return;
        }
        final String body = stripResponsePrefix(full);
        int partStart = 0;
        int part = 1;
        final int approxParts = (int) Math.ceil((double) body.length() / (chunkSize - 20));

        while (partStart < body.length()) {
            final int end = Math.min(partStart + chunkSize - 25, body.length());
            final String chunk = config.responsePrefix + " (" + part + "/" + approxParts + ") "
                + body.substring(partStart, end);
            sendChat(username, chunk, sourceType);
            partStart = end;
            part++;
        }
    }

    private void respond(final String username, final String message, final String sourceType) {
        sendChat(username, ensureResponsePrefix(message), sourceType);
    }

    private void sendChat(final String username, final String message, final String sourceType) {
        if (isPublicSource(sourceType)) {
            publicChat(message);
        } else {
            whisper(username, message);
        }
    }

    public boolean debugWhisper(final String username, final String message) {
        if (!isSafeUsername(username) || message == null || message.isBlank()) return false;
        whisper(username, message);
        return true;
    }

    private void whisper(final String username, final String message) {
        if (!isSafeUsername(username)) return;
        try {
            final String safe = ChatUtil.sanitizeChatMessage(message.replaceAll("[\r\n]", " ").strip());
            final var client = Proxy.getInstance().getClient();
            if (client == null) {
                debug("send.drop", "no active client for whisper");
                logger.debug("[OpenCraft] No active client; dropping whisper to {}.", username);
                return;
            }
            if (!client.isConnected()) {
                debug("send.drop", "client disconnected for whisper");
                logger.debug("[OpenCraft] Client disconnected; dropping whisper to {}.", username);
                return;
            }
            final String whisperCommand = com.zenith.Globals.CONFIG.client.extra.whisperCommand;
            debug("send.whisper", "cmd=/" + whisperCommand + " target=" + username + " message=" + safe);
            client.sendAsync(ChatUtil.getWhisperChatPacket(username, safe));
        } catch (final Exception e) {
            debug("send.error", "whisper " + e.getMessage());
            logger.warn("[OpenCraft] Failed to send whisper to {}: {}", username, e.getMessage());
        }
    }

    private void publicChat(final String message) {
        try {
            final var client = Proxy.getInstance().getClient();
            if (client == null) {
                debug("send.drop", "no active client for public chat");
                logger.debug("[OpenCraft] No active client; dropping public chat response.");
                return;
            }
            final String safe = ChatUtil.sanitizeChatMessage(message.replaceAll("[\r\n]", " ").strip());
            debug("send.public", safe);
            client.sendAsync(new ServerboundChatPacket(safe));
        } catch (final Exception e) {
            debug("send.error", "public " + e.getMessage());
            logger.warn("[OpenCraft] Failed to send public chat response: {}", e.getMessage());
        }
    }

    private String resolveUsername(final UUID uuid) {
        try {
            return com.zenith.Globals.CACHE.getTabListCache().get(uuid)
                .map(e -> e.getProfile() != null ? e.getProfile().getName() : e.getName())
                .orElseGet(() -> uuid.toString().substring(0, 8));
        } catch (final Exception e) {
            return uuid.toString().substring(0, 8);
        }
    }

    @Nullable
    private UUID resolveUuidByUsername(final String username) {
        try {
            return com.zenith.Globals.CACHE.getTabListCache().getFromName(username)
                .map(e -> e.getProfile() != null ? e.getProfile().getId() : e.getProfileId())
                .orElse(null);
        } catch (final Exception e) {
            return null;
        }
    }

    private String generateRequestId() {
        return "req-" + System.currentTimeMillis() + "-" + reqCounter.incrementAndGet();
    }

    private static boolean isSafeUsername(final String s) {
        return s != null && SAFE_USERNAME.matcher(s).matches();
    }

    private static boolean isPublicSource(final String sourceType) {
        return "public_chat".equals(sourceType);
    }

    private String ensureResponsePrefix(final String message) {
        final String normalized = message == null ? "" : message.strip();
        if (normalized.isBlank()) {
            return config.responsePrefix;
        }
        return normalized.startsWith(config.responsePrefix)
            ? normalized
            : config.responsePrefix + " " + normalized;
    }

    private String stripResponsePrefix(final String message) {
        final String marker = config.responsePrefix + " ";
        if (message.equals(config.responsePrefix)) {
            return "";
        }
        return message.startsWith(marker)
            ? message.substring(marker.length())
            : message;
    }

}
