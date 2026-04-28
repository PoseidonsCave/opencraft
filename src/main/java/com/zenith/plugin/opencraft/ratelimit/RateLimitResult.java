package com.zenith.plugin.opencraft.ratelimit;

/**
 * Result of a rate-limit check.
 * allowed: whether the request is permitted.
 * reason: human-readable denial reason (safe to whisper to the user).
 * retryAfterMs: milliseconds until the user may retry (0 if allowed or unavailable).
 */
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
