package com.zenith.plugin.opencraft.provider;

import java.util.function.Function;

/**
 * Mock LLM provider for unit tests and integration testing.
 * Returns a configurable response without making any network calls.
 *
 * Also used as the fallback when OPENCRAFT_API_KEY is absent and the
 * operator explicitly sets providerName = "mock" in the config.
 */
public final class MockProvider implements OpenCraftProvider {

    public static final String NAME = "mock";

    private final Function<OpenCraftRequest, String> responseFactory;

    /** Returns a fixed JSON response for every request. */
    public MockProvider(final String fixedResponse) {
        this(ignored -> fixedResponse);
    }

    /** Returns a response computed from the incoming request (useful for scenario tests). */
    public MockProvider(final Function<OpenCraftRequest, String> responseFactory) {
        this.responseFactory = responseFactory;
    }

    @Override
    public OpenCraftResponse complete(final OpenCraftRequest request) {
        return new OpenCraftResponse(request.requestId(), responseFactory.apply(request), 10, 20);
    }

    @Override
    public String name() {
        return NAME;
    }
}
