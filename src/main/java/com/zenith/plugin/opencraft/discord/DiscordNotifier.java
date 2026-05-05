package com.zenith.plugin.opencraft.discord;

import com.zenith.discord.Embed;
import com.zenith.plugin.opencraft.OpenCraftConfig;
import com.zenith.plugin.opencraft.auth.UserIdentity;
import com.zenith.plugin.opencraft.intent.CommandIntent;
import com.zenith.util.Color;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.Executor;

import static com.zenith.Globals.EXECUTOR;

public final class DiscordNotifier {

    private final OpenCraftConfig config;
    private final ComponentLogger logger;
    private final Executor        executor;

    public DiscordNotifier(final OpenCraftConfig config, final ComponentLogger logger) {
        this.config   = config;
        this.logger   = logger;
        this.executor = EXECUTOR;
    }

    public void notifyPromptReceived(final String requestId, final UserIdentity identity,
                                     final String promptExcerpt, final String sourceType) {
        if (!config.discordAuditEnabled) return;
        send(DiscordAuditPayload.from(requestId, "PROMPT_RECEIVED", identity,
            sourceType, promptExcerpt, null, null, "allowed", null, config.providerName));
    }

    public void notifyResponseSent(final String requestId, final UserIdentity identity,
                                    final String responseExcerpt) {
        if (!config.discordAuditEnabled) return;
        send(DiscordAuditPayload.from(requestId, "RESPONSE_SENT", identity,
            null, null, responseExcerpt, null, "allowed", null, config.providerName));
    }

    public void notifyDenied(final String requestId, @Nullable final UserIdentity identity,
                              final String reason) {
        if (!config.discordAuditEnabled || !config.discordLogDenied) return;
        final UserIdentity ident = identity != null ? identity
            : new UserIdentity(null, "unknown", com.zenith.plugin.opencraft.auth.UserRole.MEMBER, false);
        send(DiscordAuditPayload.from(requestId, "REQUEST_DENIED", ident,
            null, null, null, null, "denied", reason, null));
    }

    public void notifyCommandPending(final String requestId, final UserIdentity identity,
                                      final CommandIntent intent) {
        if (!config.discordAuditEnabled || !config.discordLogAdminCommands) return;
        send(DiscordAuditPayload.from(requestId, "COMMAND_PENDING", identity,
            null, null, null, intent, "pending", null, config.providerName));
    }

    public void notifyCommandExecuted(final String requestId, final UserIdentity identity,
                                       final CommandIntent intent, final String result) {
        if (!config.discordAuditEnabled || !config.discordLogAdminCommands) return;
        send(DiscordAuditPayload.from(requestId, "COMMAND_EXECUTED", identity,
            null, null, null, intent, "allowed", result, config.providerName));
    }

    public void notifyCommandFailed(final String requestId, final UserIdentity identity,
                                     final CommandIntent intent, final String reason) {
        if (!config.discordAuditEnabled || !config.discordLogAdminCommands) return;
        send(DiscordAuditPayload.from(requestId, "COMMAND_FAILED", identity,
            null, null, null, intent, "denied", reason, config.providerName));
    }

    public void notifyProviderError(final String requestId, final UserIdentity identity,
                                     final String reason) {
        if (!config.discordAuditEnabled) return;
        send(DiscordAuditPayload.from(requestId, "PROVIDER_ERROR", identity,
            null, null, null, null, "failed", reason, config.providerName));
    }

    private void send(final DiscordAuditPayload payload) {
        executor.execute(() -> {
            if (!isZenithDiscordAvailable()) {
                logger.debug("[OpenCraft] Zenith Discord bot is not running; skipping OpenCraft Discord notification.");
                return;
            }

            try {
                com.zenith.Globals.DISCORD.sendEmbedMessage(buildDiscordEmbed(payload));
            } catch (final Exception e) {
                logger.warn("[OpenCraft] Discord notification failed: {}", e.getMessage());
            }
        });
    }

    private Embed buildDiscordEmbed(final DiscordAuditPayload payload) {
        final Embed embed = Embed.builder()
            .title("OpenCraft - " + payload.eventType())
            .timestamp(payload.timestamp())
            .color(Color.fromInt(embedColor(payload.eventType())));

        addField(embed, "Request ID", payload.requestId(), true);
        addField(embed, "User", payload.username(), true);
        addField(embed, "UUID", payload.uuid(), true);
        addField(embed, "Role", payload.role(), true);
        addField(embed, "Auth Result", payload.authorizationResult(), true);
        addField(embed, "Source", payload.sourceType(), true);
        addField(embed, "Command", payload.commandId(), true);
        addField(embed, "Prompt", payload.promptExcerpt(), false);
        addField(embed, "Response", payload.responseExcerpt(), false);
        addField(embed, "Result", payload.executionResult(), false);
        addField(embed, "Provider", payload.providerName(), true);
        return embed;
    }

    private static void addField(final Embed embed, final String name,
                                 @Nullable final String value, final boolean inline) {
        if (value == null) return;
        embed.addField(name, value.substring(0, Math.min(value.length(), 1024)), inline);
    }

    private static boolean isZenithDiscordAvailable() {
        try {
            return com.zenith.Globals.CONFIG.discord.enable
                && com.zenith.Globals.DISCORD != null
                && com.zenith.Globals.DISCORD.isRunning();
        } catch (final Exception e) {
            return false;
        }
    }

    private static int embedColor(final String type) {
        return switch (type) {
            case "REQUEST_DENIED", "COMMAND_DENIED", "COMMAND_FAILED" -> 0xFF0000; // red
            case "COMMAND_EXECUTED", "RESPONSE_SENT"                  -> 0x00CC44; // green
            case "COMMAND_PENDING"                                     -> 0xFFAA00; // orange
            case "PROVIDER_ERROR"                                      -> 0xFF6600; // dark orange
            default                                                    -> 0x5865F2; // Discord blurple
        };
    }
}
