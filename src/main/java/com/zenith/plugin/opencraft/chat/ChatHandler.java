package com.zenith.plugin.opencraft.chat;

import com.zenith.Proxy;
import com.zenith.util.ChatUtil;
import com.zenith.plugin.opencraft.OpenCraftConfig;
import com.zenith.plugin.opencraft.audit.AuditEvent;
import com.zenith.plugin.opencraft.audit.AuditLogger;
import com.zenith.plugin.opencraft.auth.AuthorizationService;
import com.zenith.plugin.opencraft.auth.UserIdentity;
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

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Receives raw Minecraft chat events and orchestrates the full LLM pipeline:
 * observe → auth → rate limiting → prompt building → LLM call → response parsing
 * → (optional) command/plan execution → whisper delivery → audit/Discord logging.
 *
 * All LLM calls are dispatched to an off-thread executor to avoid blocking
 * the game network thread.
 */
public final class ChatHandler {

    private static final int MAX_USERNAME_LEN = 16;
    private static final Pattern SAFE_USERNAME = Pattern.compile("[a-zA-Z0-9_]{1,16}");
    private static final Pattern COLOR_CODE    = Pattern.compile("§[0-9a-fk-or]");

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
                       final ComponentLogger logger) {
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
        this.llmExecutor        = Executors.newCachedThreadPool(
            r -> { final Thread t = new Thread(r, "opencraft-worker"); t.setDaemon(true); return t; }
        );
    }

    // ── Entry points called by OpenCraftModule ─────────────────────────────────────

    /**
     * Handle a signed player chat packet. UUID is directly available and trusted.
     */
    public void onPlayerChat(final UUID senderUuid, final String rawMessage) {
        if (!config.publicChatEnabled) return;
        final String stripped = stripColorCodes(rawMessage);
        if (!stripped.startsWith(config.prefix)) return;

        final String userInput = stripped.substring(config.prefix.length()).strip();
        // UUID is available; username is not — look it up from CACHE
        final String username = resolveUsername(senderUuid);
        handleRequest(senderUuid, username, userInput, "public_chat");
    }

    /**
     * Handle a system chat packet (whispers, server messages, etc.).
     * UUID is not directly available; resolved via CACHE after username extraction.
     */
    public void onSystemChat(final Object formattedContent) {
        if (!config.whisperEnabled) return;
        final String plain = extractPlainText(formattedContent);
        if (plain == null) return;

        // Check whisper pattern
        final Matcher m = compileWhisperPattern(config.whisperInboundPattern).matcher(plain);
        if (!m.matches()) return;
        final String senderName = m.group(1);
        final String rawMessage = m.group(2);

        if (!rawMessage.startsWith(config.prefix)) return;
        final String userInput = rawMessage.substring(config.prefix.length()).strip();

        // Try to resolve UUID from tab-list cache
        final UUID uuid = resolveUuidByUsername(senderName);
        handleRequest(uuid, senderName, userInput, "whisper");
    }

    // ── Core pipeline ─────────────────────────────────────────────────────────

    private void handleRequest(@Nullable final UUID uuid,
                                final String username,
                                final String userInput,
                                final String sourceType) {
        final String requestId = generateRequestId();

        // ── Validate username format before using it ───────────────────────
        if (!isSafeUsername(username)) {
            logger.debug("[OpenCraft] req={} Rejected message with unsafe username.", requestId);
            return;
        }

        // ── Special keywords: confirm / cancel ────────────────────────────
        final Optional<UserIdentity> identityOpt = authService.resolve(uuid, username);
        if (identityOpt.isPresent() && "confirm".equalsIgnoreCase(userInput)) {
            handleConfirm(identityOpt.get(), username, requestId);
            return;
        }
        if (identityOpt.isPresent() && "cancel".equalsIgnoreCase(userInput)) {
            handleCancel(identityOpt.get(), username, requestId);
            return;
        }

        // ── Authorization check ────────────────────────────────────────────
        if (identityOpt.isEmpty()) {
            // Not whitelisted — log silently, send no response
            if (config.logDeniedAttempts) {
                auditLogger.log(AuditEvent.requestDenied(requestId, username, "Not whitelisted"));
                discordNotifier.notifyDenied(requestId, null, "Not whitelisted");
            }
            return;
        }

        final UserIdentity identity = identityOpt.get();
        final String rateKey = uuid != null ? uuid.toString() : username;

        // ── Rate limit check ───────────────────────────────────────────────
        final RateLimitResult rateResult = rateLimiter.check(rateKey);
        if (!rateResult.allowed()) {
            auditLogger.log(AuditEvent.rateLimited(requestId, identity, rateResult.reason()));
            whisper(username, config.responsePrefix + " " + rateResult.reason());
            return;
        }

        rateLimiter.recordRequest(rateKey);

        // ── Dispatch to executor ──────────────────────────────────────────
        if (!rateLimiter.acquireConcurrencySlot()) {
            whisper(username, config.responsePrefix + " The assistant is busy. Please try again shortly.");
            return;
        }

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
        auditLogger.log(AuditEvent.promptReceived(requestId, identity, userInput));
        discordNotifier.notifyPromptReceived(requestId, identity, userInput, sourceType);

        final WorldState worldState = worldStateObserver.observe();
        final String systemPrompt = promptBuilder.build(identity, requestId, worldState);
        final OpenCraftRequest req = new OpenCraftRequest(requestId, systemPrompt, userInput);

        final OpenCraftResponse response;
        try {
            response = provider.complete(req);
        } catch (final OpenCraftProviderException e) {
            logger.warn("[OpenCraft] req={} Provider error: {}", requestId, e.getMessage());
            auditLogger.log(AuditEvent.providerError(requestId, identity, e.getMessage()));
            discordNotifier.notifyProviderError(requestId, identity, e.getMessage());
            whisper(identity.username(), config.responsePrefix + " The assistant is temporarily unavailable.");
            return;
        }

        rateLimiter.recordTokens(response.totalTokens());

        final var parsed = intentParser.parse(response.rawContent(), requestId);

        if (parsed instanceof PlainResponse plain) {
            final String content = plain.content();
            auditLogger.log(AuditEvent.responseSent(requestId, identity, content, provider.name()));
            discordNotifier.notifyResponseSent(requestId, identity, content);
            chunkWhisper(identity.username(), content);
            return;
        }

        if (parsed instanceof CommandIntentResponse commandIntent) {
            final ExecutionResult result =
                commandExecutor.execute(commandIntent.intent(), identity, requestId);
            whisper(identity.username(), result.message());
            return;
        }

        if (parsed instanceof PlanResponse planResponse) {
            if (!config.operationsEnabled) {
                whisper(identity.username(), config.responsePrefix + " Operations are not enabled.");
                return;
            }
            final OperationalPlan plan = planResponse.plan();
            final String costWarning = (config.operationCostWarnThreshold > 0
                && plan.costEstimate() > config.operationCostWarnThreshold)
                ? " (estimated token cost: " + plan.costEstimate() + ")" : "";
            if (plan.requiresApproval()) {
                operationExecutor.stagePlan(plan, identity);
                whisper(identity.username(), config.responsePrefix + " Plan: " + plan.summary()
                    + costWarning + " — Reply 'confirm' to proceed or 'cancel' to abort.");
            } else {
                whisper(identity.username(), config.responsePrefix + " Executing: " + plan.summary()
                    + costWarning);
                operationExecutor.startOperation(plan, identity, requestId,
                    msg -> whisper(identity.username(), config.responsePrefix + " " + msg));
            }
            auditLogger.log(AuditEvent.operationStarted(requestId, identity,
                plan.steps().size(), plan.risk()));
            return;
        }

        if (parsed instanceof RefusalResponse refusal) {
            auditLogger.log(AuditEvent.requestDenied(requestId, identity.username(), refusal.reason()));
            chunkWhisper(identity.username(), refusal.reason());
            return;
        }

        if (parsed instanceof ClarificationResponse clarification) {
            chunkWhisper(identity.username(), clarification.message());
        }
    }

    // ── Confirm / Cancel ──────────────────────────────────────────────────────

    private void handleConfirm(final UserIdentity identity, final String username,
                                 final String requestId) {
        // Check for a staged multi-step operation first
        if (config.operationsEnabled && operationExecutor.hasStagedPlan(identity)) {
            operationExecutor.startStagedOperation(identity, requestId,
                msg -> whisper(username, config.responsePrefix + " " + msg));
            return;
        }
        if (!commandExecutor.hasPendingConfirmation(identity)) {
            whisper(username, config.responsePrefix + " No pending command to confirm.");
            return;
        }
        final ExecutionResult result = commandExecutor.confirm(identity, requestId);
        whisper(username, result.message());
    }

    private void handleCancel(final UserIdentity identity, final String username,
                                final String requestId) {
        boolean cancelled = false;
        if (config.operationsEnabled) {
            operationExecutor.cancelOperation(identity);
            cancelled = true;
        }
        final boolean cmdCancelled = commandExecutor.cancel(identity);
        if (cancelled || cmdCancelled) {
            whisper(username, config.responsePrefix + " Cancelled.");
        } else {
            whisper(username, config.responsePrefix + " Nothing to cancel.");
        }
    }

    // ── Messaging helpers ─────────────────────────────────────────────────────

    /**
     * Send a whisper to the player, chunked into safe lengths.
     */
    private void chunkWhisper(final String username, final String message) {
        if (message == null || message.isBlank()) return;
        final String full = config.responsePrefix + " " + message;
        final int chunkSize = config.whisperChunkSize;

        if (full.length() <= chunkSize) {
            whisper(username, full);
            return;
        }

        // Split into chunks, adding part numbers
        final String body = message;
        int partStart = 0;
        int part = 1;
        final int approxParts = (int) Math.ceil((double) body.length() / (chunkSize - 20));

        while (partStart < body.length()) {
            final int end = Math.min(partStart + chunkSize - 25, body.length());
            final String chunk = config.responsePrefix + " (" + part + "/" + approxParts + ") "
                + body.substring(partStart, end);
            whisper(username, chunk);
            partStart = end;
            part++;
        }
    }

    private void whisper(final String username, final String message) {
        if (!isSafeUsername(username)) return;
        try {
            // Ensure no newlines or injection into the chat command
            final String safe = message.replaceAll("[\r\n]", " ").strip();
            final var client = Proxy.getInstance().getClient();
            if (client == null) {
                logger.debug("[OpenCraft] No active client; dropping whisper to {}.", username);
                return;
            }
            client.sendAsync(ChatUtil.getWhisperChatPacket(username, safe));
        } catch (final Exception e) {
            logger.warn("[OpenCraft] Failed to send whisper to {}: {}", username, e.getMessage());
        }
    }

    // ── Identity helpers ──────────────────────────────────────────────────────

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

    // ── Utilities ─────────────────────────────────────────────────────────────

    private String generateRequestId() {
        return "req-" + System.currentTimeMillis() + "-" + reqCounter.incrementAndGet();
    }

    private static boolean isSafeUsername(final String s) {
        return s != null && SAFE_USERNAME.matcher(s).matches();
    }

    private static String stripColorCodes(final String s) {
        return COLOR_CODE.matcher(s).replaceAll("");
    }

    /** Extract plain text from a MCProtocolLib component (or return null if not a chat-visible message). */
    @Nullable
    private static String extractPlainText(final Object component) {
        if (component == null) return null;
        // MCProtocolLib components implement toString() as plain text in most versions.
        // For a production implementation, use the appropriate component.asUnformattedString() API.
        final String text = component.toString();
        return COLOR_CODE.matcher(text).replaceAll("");
    }

    private static Pattern compileWhisperPattern(final String patternStr) {
        try {
            return Pattern.compile(patternStr);
        } catch (final PatternSyntaxException e) {
            // Fall back to a safe default if the config pattern is invalid
            return Pattern.compile("^(\\S+) whispers to you: (.+)$");
        }
    }
}
