package com.zenith.plugin.opencraft.intent;

import java.time.Instant;

public record PendingConfirmation(
    CommandIntent    intent,
    CommandDefinition definition,
    Instant          expiresAt
) {
    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }
}
