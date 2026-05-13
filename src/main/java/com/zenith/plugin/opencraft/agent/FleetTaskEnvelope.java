package com.zenith.plugin.opencraft.agent;

import java.time.Instant;
import java.util.List;

public record FleetTaskEnvelope(
    String fleetRequestId,
    String initiatedBy,
    String originNodeId,
    List<String> targetPeerIds,
    Instant createdAt,
    boolean sharedBillingEnabled,
    int billableRequestUnits,
    int downstreamDispatchCount
) {
}
