package com.zenith.plugin.opencraft.provider;

public record OpenCraftRequest(
    String requestId,
    String systemPrompt,
    String userMessage
) {}
