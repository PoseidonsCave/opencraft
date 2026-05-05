package com.zenith.plugin.opencraft.provider;

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
