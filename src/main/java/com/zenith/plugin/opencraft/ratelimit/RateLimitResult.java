package com.zenith.plugin.opencraft.ratelimit;

public record RateLimitResult(
    boolean allowed,
    String  reason,
    long    retryAfterMs
) {
    public static RateLimitResult allow() {
        return new RateLimitResult(true, "", 0);
    }

    public static RateLimitResult deny(final String reason, final long retryAfterMs) {
        return new RateLimitResult(false, reason, retryAfterMs);
    }
}
