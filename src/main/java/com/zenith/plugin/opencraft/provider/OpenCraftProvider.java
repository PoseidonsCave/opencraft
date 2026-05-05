package com.zenith.plugin.opencraft.provider;

public interface OpenCraftProvider {

        OpenCraftResponse complete(OpenCraftRequest request) throws OpenCraftProviderException;

        String name();
}
