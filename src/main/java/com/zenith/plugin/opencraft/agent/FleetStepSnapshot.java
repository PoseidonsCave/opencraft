package com.zenith.plugin.opencraft.agent;

import java.time.Instant;

public record FleetStepSnapshot(
    String stepId,
    String targetPeerId,
    String commandId,
    String summary,
    FleetStepStatus status,
    Instant createdAt,
    Instant updatedAt,
    String detail
) {
    public String compactSummary() {
        return stepId + " -> " + targetPeerId + " / " + commandId + " / " + status.configValue();
    }
}
