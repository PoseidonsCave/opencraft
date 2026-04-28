package com.zenith.plugin.opencraft.discord;

import com.zenith.plugin.opencraft.auth.UserIdentity;
import com.zenith.plugin.opencraft.intent.CommandIntent;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

/**
 * Immutable payload for optional OpenCraft Discord notifications.
 * All fields have already been redacted by the time this record is constructed.
 */
public record DiscordAuditPayload(
    Instant    timestamp,
    String     requestId,
    String     eventType,
    String     username,
    @Nullable String uuid,
    String     role,
    @Nullable String sourceType,        // "whisper" | "public_chat"
    @Nullable String promptExcerpt,
    @Nullable String responseExcerpt,
    @Nullable String commandId,
    @Nullable String commandExplanation,
    String     authorizationResult,     // "allowed" | "denied" | "pending"
    @Nullable String executionResult,
    @Nullable String providerName
) {
    /** Truncate + strip dangerous patterns from any text that goes into Discord. */
    public static String sanitise(@Nullable final String s) {
        if (s == null) return null;
        final String cleaned = s.replaceAll("[\r\n]+", " ")
                .replaceAll("@everyone|@here", "[mention removed]")
                .strip();
        return cleaned.substring(0, Math.min(cleaned.length(), 256));
    }

    public static DiscordAuditPayload from(final String requestId,
                                           final String eventType,
                                           final UserIdentity identity,
                                           @Nullable final String sourceType,
                                           @Nullable final String promptExcerpt,
                                           @Nullable final String responseExcerpt,
                                           @Nullable final CommandIntent intent,
                                           final String authResult,
                                           @Nullable final String executionResult,
                                           @Nullable final String providerName) {
        return new DiscordAuditPayload(
            Instant.now(),
            requestId,
            eventType,
            sanitise(identity.username()),
            identity.uuid() != null ? identity.uuid().toString() : null,
            identity.role().name(),
            sourceType,
            sanitise(promptExcerpt),
            sanitise(responseExcerpt),
            intent != null ? intent.commandId() : null,
            intent != null ? sanitise(intent.explanation()) : null,
            authResult,
            sanitise(executionResult),
            providerName
        );
    }
}
