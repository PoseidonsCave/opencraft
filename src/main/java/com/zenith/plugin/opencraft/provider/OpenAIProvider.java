package com.zenith.plugin.opencraft.provider;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * OpenAI-compatible chat completions provider.
 * Use this for OpenAI-compatible endpoints that expose POST /chat/completions.
 * Read API keys from env on each call. Never log or cache secrets.
 */
public final class OpenAIProvider implements OpenCraftProvider {

    private static final String COMPLETIONS_PATH = "/chat/completions";

    private final ProviderConfig  config;
    private final HttpClient      httpClient;
    private final Gson            gson;
    private final ComponentLogger logger;

    public OpenAIProvider(final ProviderConfig config, final ComponentLogger logger) {
        this.config  = config;
        this.logger  = logger;
        this.gson    = new Gson();
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    @Override
    public OpenCraftResponse complete(final OpenCraftRequest request) throws OpenCraftProviderException {
        // ── Resolve API key from environment — NEVER from a stored field ──────
        final String apiKey = System.getenv(config.apiKeyEnvVar());
        if (apiKey == null || apiKey.isBlank()) {
            throw new OpenCraftProviderException(
                "API key environment variable '" + config.apiKeyEnvVar() + "' is not set or is blank");
        }

        final URI uri = buildUri();
        final String body = buildRequestBody(request);

        final HttpRequest httpRequest = HttpRequest.newBuilder(uri)
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + apiKey)   // key used in-flight only
            .timeout(Duration.ofSeconds(config.timeoutSeconds()))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        return sendWithRetry(httpRequest, request.requestId());
    }

    private OpenCraftResponse sendWithRetry(final HttpRequest httpRequest,
                                      final String requestId) throws OpenCraftProviderException {
        int attempt = 0;
        while (true) {
            try {
                final HttpResponse<String> resp =
                    httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

                // Provider rate limit — retry with back-off
                if (resp.statusCode() == 429 && attempt < config.maxRetries()) {
                    sleepForRetry(++attempt);
                    continue;
                }
                // Transient server error — retry
                if (resp.statusCode() >= 500 && attempt < config.maxRetries()) {
                    sleepForRetry(++attempt);
                    continue;
                }
                // Auth / client error — do not retry; log status only (not body)
                if (resp.statusCode() == 401 || resp.statusCode() == 403) {
                    logger.warn("[OpenCraft] Provider auth failure (HTTP {}). " +
                        "Check that '{}' is set correctly.", resp.statusCode(), config.apiKeyEnvVar());
                    throw new OpenCraftProviderException("Provider authentication failed (HTTP " + resp.statusCode() + ")");
                }
                if (resp.statusCode() >= 400) {
                    logger.warn("[OpenCraft] Provider returned HTTP {}.", resp.statusCode());
                    throw new OpenCraftProviderException("Provider returned HTTP " + resp.statusCode());
                }

                return parseResponse(resp.body(), requestId);

            } catch (IOException e) {
                if (attempt < config.maxRetries()) { sleepForRetry(++attempt); continue; }
                throw new OpenCraftProviderException("Provider I/O error after " + attempt + " retries: " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new OpenCraftProviderException("Request interrupted");
            }
        }
    }

    private URI buildUri() throws OpenCraftProviderException {
        String base = config.baseUrl();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        try {
            return URI.create(base + COMPLETIONS_PATH);
        } catch (final IllegalArgumentException e) {
            throw new OpenCraftProviderException("Invalid provider base URL: " + e.getMessage());
        }
    }

    private String buildRequestBody(final OpenCraftRequest request) {
        final JsonObject body = new JsonObject();
        body.addProperty("model",      config.model());
        body.addProperty("max_tokens", config.maxOutputTokens());
        body.addProperty("temperature", config.temperature());

        final JsonArray messages = new JsonArray();

        final JsonObject sysMsg = new JsonObject();
        sysMsg.addProperty("role", "system");
        sysMsg.addProperty("content", request.systemPrompt());
        messages.add(sysMsg);

        final JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", truncate(request.userMessage(), config.maxInputLength()));
        messages.add(userMsg);

        body.add("messages", messages);
        return gson.toJson(body);
    }

    private OpenCraftResponse parseResponse(final String responseBody,
                                      final String requestId) throws OpenCraftProviderException {
        try {
            final JsonObject json = gson.fromJson(responseBody, JsonObject.class);
            if (json == null) throw new OpenCraftProviderException("Empty JSON from provider");

            final var choices = json.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) {
                throw new OpenCraftProviderException("No choices in provider response");
            }

            final var message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
            if (message == null) throw new OpenCraftProviderException("Missing message object in response choice");

            final String content = (message.has("content") && !message.get("content").isJsonNull())
                ? message.get("content").getAsString()
                : "";

            int promptTokens = 0, completionTokens = 0;
            if (json.has("usage")) {
                final var usage = json.getAsJsonObject("usage");
                promptTokens     = getInt(usage, "prompt_tokens");
                completionTokens = getInt(usage, "completion_tokens");
            }

            return new OpenCraftResponse(requestId, content, promptTokens, completionTokens);
        } catch (final JsonSyntaxException e) {
            throw new OpenCraftProviderException("Malformed JSON from provider: " + e.getMessage());
        }
    }

    private static int getInt(final JsonObject obj, final String key) {
        return (obj.has(key) && !obj.get(key).isJsonNull()) ? obj.get(key).getAsInt() : 0;
    }

    private static String truncate(final String s, final int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }

    private void sleepForRetry(final int attempt) {
        try {
            Thread.sleep(config.retryDelayMs() * attempt);
        } catch (final InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public String name() {
        return config.providerName();
    }
}
