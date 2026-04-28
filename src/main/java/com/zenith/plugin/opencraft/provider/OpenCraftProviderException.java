package com.zenith.plugin.opencraft.provider;

/**
 * Thrown by OpenCraftProvider implementations for any provider-level failure.
 * Messages must not contain API keys, response bodies that may include sensitive data,
 * or other secrets.
 */
public final class OpenCraftProviderException extends Exception {
    public OpenCraftProviderException(final String message) {
        super(message);
    }

    public OpenCraftProviderException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
