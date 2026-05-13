package com.zenith.plugin.opencraft.agent;

import com.zenith.plugin.opencraft.OpenCraftConfig;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentNetworkServiceTest {

    @Test
    void sharedBillingCollapsesFanoutToSingleUnit() {
        final OpenCraftConfig config = baseConfig();
        config.agent.shareBillingAcrossPeers = true;

        final AgentNetworkService service = new AgentNetworkService(
            config,
            new ChallengePhraseService(),
            new FleetRunService(25),
            Clock.fixed(Instant.parse("2026-05-12T12:00:00Z"), ZoneOffset.UTC),
            key -> "shared-secret"
        );

        final FleetTaskEnvelope envelope = service.draftFleetTask("michael", List.of("bot1", "bot2", "bot3"));

        assertEquals(3, envelope.downstreamDispatchCount());
        assertEquals(1, envelope.billableRequestUnits());
    }

    @Test
    void unsharedBillingTracksPeerCount() {
        final OpenCraftConfig config = baseConfig();
        config.agent.shareBillingAcrossPeers = false;

        final AgentNetworkService service = new AgentNetworkService(
            config,
            new ChallengePhraseService(),
            new FleetRunService(25),
            Clock.fixed(Instant.parse("2026-05-12T12:00:00Z"), ZoneOffset.UTC),
            key -> "shared-secret"
        );

        final FleetTaskEnvelope envelope = service.draftFleetTask("michael", List.of("bot1", "bot2", "bot2"));

        assertEquals(2, envelope.downstreamDispatchCount());
        assertEquals(2, envelope.billableRequestUnits());
    }

    @Test
    void challengeVerificationHonorsClockWindow() {
        final OpenCraftConfig config = baseConfig();
        final Clock clock = Clock.fixed(Instant.parse("2026-05-12T12:00:00Z"), ZoneOffset.UTC);

        final AgentNetworkService service = new AgentNetworkService(
            config,
            new ChallengePhraseService(),
            new FleetRunService(25),
            clock,
            key -> "shared-secret"
        );

        final AgentChallenge challenge = service.issueOnboardingChallenge("bot2", List.of("execute")).orElseThrow();

        assertTrue(service.verifyOnboardingResponse(
            challenge,
            challenge.phrase(),
            Instant.parse("2026-05-12T12:01:30Z")
        ));
    }

    @Test
    void agentProfileCanAcceptTasksButCannotCoordinate() {
        final OpenCraftConfig config = baseConfig();
        config.profile = "agent";

        final AgentNetworkService service = new AgentNetworkService(
            config,
            new ChallengePhraseService(),
            new FleetRunService(25),
            Clock.fixed(Instant.parse("2026-05-12T12:00:00Z"), ZoneOffset.UTC),
            key -> "shared-secret"
        );

        assertTrue(service.canAcceptFleetTasks());
        assertFalse(service.canCoordinateFleet());
    }

    @Test
    void managerCreatesFleetRunWithDedupedTargetsAndSharedBilling() {
        final OpenCraftConfig config = baseConfig();
        config.profile = "manager";
        config.agent.shareBillingAcrossPeers = true;
        final Clock clock = Clock.fixed(Instant.parse("2026-05-12T12:00:00Z"), ZoneOffset.UTC);

        final AgentNetworkService service = new AgentNetworkService(
            config,
            new ChallengePhraseService(),
            new FleetRunService(clock, 25),
            clock,
            key -> "shared-secret"
        );

        final FleetRunSnapshot run = service.createFleetRun(
            "terminal",
            "Coordinate PearlPlus refresh",
            List.of("bot2", "bot2")
        ).orElseThrow();

        assertEquals("draft", run.status().configValue());
        assertEquals(List.of("bot2"), run.targetPeerIds());
        assertEquals(1, run.billableRequestUnits());
        assertTrue(run.sharedBillingEnabled());
        assertNotNull(run.runId());
    }

    @Test
    void managerRejectsFleetRunForUnknownPeer() {
        final OpenCraftConfig config = baseConfig();
        config.profile = "manager";

        final AgentNetworkService service = new AgentNetworkService(
            config,
            new ChallengePhraseService(),
            new FleetRunService(25),
            Clock.fixed(Instant.parse("2026-05-12T12:00:00Z"), ZoneOffset.UTC),
            key -> "shared-secret"
        );

        assertTrue(service.createFleetRun(
            "terminal",
            "Coordinate PearlPlus refresh",
            List.of("bot2")
        ).isPresent());
        assertFalse(service.createFleetRun(
            "terminal",
            "Coordinate PearlPlus refresh",
            List.of("bot9")
        ).isPresent());
    }

    private static OpenCraftConfig baseConfig() {
        final OpenCraftConfig config = new OpenCraftConfig();
        config.agent.enabled = true;
        config.agent.nodeId = "bot1";
        config.agent.cluster = "alpha";
        config.agent.challengeTtlSeconds = 120;
        config.agent.allowedClockSkewSeconds = 30;
        config.agent.sharedSecretEnvVar = "OPENCRAFT_AGENT_SECRET";

        final OpenCraftConfig.AgentPeerConfig peer = new OpenCraftConfig.AgentPeerConfig();
        peer.peerId = "bot2";
        peer.displayName = "Bot 2";
        peer.host = "10.0.0.2";
        peer.port = 38265;
        config.agent.peers.add(peer);
        return config;
    }
}
