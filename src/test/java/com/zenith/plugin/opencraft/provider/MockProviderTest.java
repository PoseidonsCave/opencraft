package com.zenith.plugin.opencraft.provider;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MockProviderTest {

    @Test
    void fixedResponse_returnsExpected() throws OpenCraftProviderException {
        final MockProvider provider = new MockProvider("{\"type\":\"response\",\"content\":\"hello\"}");
        final OpenCraftResponse response = provider.complete(
            new OpenCraftRequest("req-1", "system", "user message"));
        assertEquals("{\"type\":\"response\",\"content\":\"hello\"}", response.rawContent());
        assertEquals("req-1", response.requestId());
    }

    @Test
    void functionProvider_computesResponse() throws OpenCraftProviderException {
        final MockProvider provider = new MockProvider(req ->
            "{\"type\":\"response\",\"content\":\"echo: " + req.userMessage() + "\"}");
        final OpenCraftResponse response = provider.complete(
            new OpenCraftRequest("req-2", "system", "hello world"));
        assertTrue(response.rawContent().contains("hello world"));
    }

    @Test
    void tokenCounts_nonZero() throws OpenCraftProviderException {
        final MockProvider provider = new MockProvider("{}");
        final OpenCraftResponse response = provider.complete(
            new OpenCraftRequest("req-3", "system", "msg"));
        assertTrue(response.totalTokens() > 0);
    }

    @Test
    void name_returnsMock() {
        assertEquals(MockProvider.NAME, new MockProvider("{}").name());
    }
}
