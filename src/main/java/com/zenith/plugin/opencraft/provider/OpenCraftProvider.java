package com.zenith.plugin.opencraft.provider;

/**
 * Abstraction over any OpenAI-compatible LLM back-end.
 * Implementations must:
 * 
 *   - Load the API key from System.getenv() at call time — never cache it.
 *   - Never log, throw, or return the raw API key value.
 *   - Be thread-safe (called from an executor pool).
 * 
 */
public interface OpenCraftProvider {

    /**
     * Send a completion request and return the model's response.
     * request: fully-constructed request (system prompt + user message).
     * Returns the model's response, or throws OpenCraftProviderException on
     * any provider-level failure (auth, timeout, rate limit, etc.).
     */
    OpenCraftResponse complete(OpenCraftRequest request) throws OpenCraftProviderException;

    /** Logical name used in audit logs and Discord notifications. */
    String name();
}
