package com.zenith.plugin.opencraft.agent;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.Map;

public record FleetRunEvent(
    String eventId,
    @Nullable String previousEventId,
    Instant timestamp,
    String runId,
    FleetEventType type,
    @Nullable String stepId,
    @Nullable String targetPeerId,
    String message,
    Map<String, String> details
) {
}
