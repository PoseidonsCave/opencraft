package com.zenith.plugin.opencraft.intent;

import java.time.Instant;

/**
 * A high-risk command intent awaiting explicit admin confirmation.
 * intent: the validated intent awaiting approval.
 * definition: the matched allowlist entry.
 * expiresAt: wall-clock instant after which the confirmation expires.
 */
public record PendingConfirmation(
    CommandIntent    intent,
    CommandDefinition definition,
    Instant          expiresAt
) {
    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }
}
