package com.zenith.plugin.opencraft.provider;

/**
 * Immutable snapshot of provider configuration used at call time.
 * Constructed from com.zenith.plugin.opencraft.OpenCraftConfig by ProviderFactory.
 * providerName: logical name for logs and Discord.
 * baseUrl: API base URL (for example, "https://api.openai.com/v1").
 * model: model identifier.
 * apiKeyEnvVar: env var containing the real API key; the key is never stored here.
 * timeoutSeconds: per-request timeout.
 * maxInputLength: maximum characters accepted from a user message.
 * maxOutputTokens: maximum output tokens.
 * temperature: sampling temperature.
 * maxRetries: retries on transient failures.
 * retryDelayMs: base retry delay in milliseconds.
 */
public record ProviderConfig(
    String providerName,
    String baseUrl,
    String model,
    String apiKeyEnvVar,
    int    timeoutSeconds,
    int    maxInputLength,
    int    maxOutputTokens,
    double temperature,
    int    maxRetries,
    long   retryDelayMs
) {}
