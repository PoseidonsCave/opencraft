package com.zenith.plugin.opencraft.provider;

/**
 * Immutable request handed to an OpenCraftProvider.
 * requestId: unique ID for correlation in audit logs.
 * systemPrompt: operator-constructed system prompt; must not contain secrets.
 * userMessage: player input after prefix stripping; always treated as adversarial.
 */
public record OpenCraftRequest(
    String requestId,
    String systemPrompt,
    String userMessage
) {}
