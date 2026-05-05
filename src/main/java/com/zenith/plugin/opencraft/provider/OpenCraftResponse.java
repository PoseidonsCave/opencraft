package com.zenith.plugin.opencraft.provider;

public record OpenCraftResponse(
    String requestId,
    String rawContent,
    int    promptTokens,
    int    completionTokens
) {
    public int totalTokens() {
        return promptTokens + completionTokens;
    }
}
