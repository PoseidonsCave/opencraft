package com.zenith.plugin.opencraft.provider;

/**
 * Immutable response returned by an OpenCraftProvider.
 * requestId: echoed from the originating OpenCraftRequest.
 * rawContent: raw string content returned by the model.
 * promptTokens: input tokens consumed (0 if unavailable).
 * completionTokens: output tokens produced (0 if unavailable).
 */
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
