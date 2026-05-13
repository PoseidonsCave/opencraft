package com.zenith.plugin.opencraft.agent;

import java.time.Instant;
import java.util.List;

public record FleetRunSnapshot(
    String runId,
    String initiatedBy,
    String originNodeId,
    String profile,
    String requestSummary,
    Instant createdAt,
    Instant updatedAt,
    FleetRunStatus status,
    boolean sharedBillingEnabled,
    int billableRequestUnits,
    List<String> targetPeerIds,
    List<FleetStepSnapshot> steps,
    List<FleetRunEvent> events
) {
    public String compactSummary() {
        return runId + " / " + status.configValue()
            + " / peers=" + targetPeerIds.size()
            + " / steps=" + steps.size()
            + " / billable=" + billableRequestUnits;
    }
}
