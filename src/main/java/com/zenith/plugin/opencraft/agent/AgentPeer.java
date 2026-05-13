package com.zenith.plugin.opencraft.agent;

public record AgentPeer(
    String peerId,
    String displayName,
    String host,
    int port,
    String role,
    boolean enabled,
    boolean allowTaskExecution
) {
    public String label() {
        return displayName == null || displayName.isBlank() ? peerId : displayName + " (" + peerId + ")";
    }

    public String endpoint() {
        return host + ":" + port;
    }
}
