package com.zenith.plugin.opencraft.provider;

import java.util.function.Function;

public final class MockProvider implements OpenCraftProvider {

    public static final String NAME = "mock";

    private final Function<OpenCraftRequest, String> responseFactory;

        public MockProvider(final String fixedResponse) {
        this(ignored -> fixedResponse);
    }

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
