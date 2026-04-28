package com.zenith.plugin.opencraft.provider;

import com.zenith.plugin.opencraft.OpenCraftConfig;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

/** Constructs the appropriate OpenCraftProvider from the operator configuration. */
public final class ProviderFactory {

    private ProviderFactory() {}

    public static OpenCraftProvider create(final OpenCraftConfig config, final ComponentLogger logger) {
        if (MockProvider.NAME.equalsIgnoreCase(config.providerName)) {
            logger.warn("[OpenCraft] Mock provider active — no real LLM calls will be made.");
            return new MockProvider("{\"type\":\"response\",\"content\":\"[mock response]\"}");
        }

        final ProviderConfig pc = new ProviderConfig(
            config.providerName,
            config.providerBaseUrl,
            config.model,
            config.apiKeyEnvVar,
            config.timeoutSeconds,
            config.maxInputLength,
            config.maxOutputTokens,
            config.temperature,
            config.maxRetries,
            config.retryDelayMs
        );
        return new OpenAIProvider(pc, logger);
    }
}
