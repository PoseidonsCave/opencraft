package com.zenith.plugin.opencraft.agent;

public record AgentNetworkStatus(
    boolean enabled,
    boolean configured,
    String profile,
    String nodeId,
    String cluster,
    String bindHost,
    int port,
    String sharedSecretEnvVar,
    boolean sharedSecretPresent,
    int configuredPeers,
    int actionablePeers,
    boolean sharedBillingAcrossPeers
) {
    public String summary() {
        return "agent=" + enabled
            + ", configured=" + configured
            + ", profile=" + profile
            + ", nodeId=" + nodeId
            + ", cluster=" + cluster
            + ", peers=" + configuredPeers
            + ", actionablePeers=" + actionablePeers
            + ", sharedBilling=" + sharedBillingAcrossPeers;
    }
}
