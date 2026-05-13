package com.zenith.plugin.opencraft.agent;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

public record AgentChallenge(
    String challengeId,
    String initiatorNodeId,
    String candidatePeerId,
    String phrase,
    Instant issuedAt,
    Instant expiresAt,
    List<String> scopes
) {
    public boolean isExpired(final Clock clock) {
        return clock.instant().isAfter(expiresAt);
    }

    public long ttlSecondsRemaining(final Clock clock) {
        return Math.max(0L, expiresAt.getEpochSecond() - clock.instant().getEpochSecond());
    }
}
