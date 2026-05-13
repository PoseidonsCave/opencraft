package com.zenith.plugin.opencraft.agent;

import com.zenith.plugin.opencraft.OpenCraftConfig;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

public final class AgentNetworkService {
    private final OpenCraftConfig config;
    private final ChallengePhraseService challengePhraseService;
    private final Clock clock;
    private final Function<String, String> envResolver;
    private final FleetRunService fleetRunService;

    public AgentNetworkService(final OpenCraftConfig config) {
        this(
            config,
            new ChallengePhraseService(),
            new FleetRunService(config.agent.maxRetainedRuns),
            Clock.systemUTC(),
            System::getenv
        );
    }

    AgentNetworkService(final OpenCraftConfig config,
                        final ChallengePhraseService challengePhraseService,
                        final FleetRunService fleetRunService,
                        final Clock clock,
                        final Function<String, String> envResolver) {
        this.config = config;
        this.challengePhraseService = challengePhraseService;
        this.fleetRunService = fleetRunService;
        this.clock = clock;
        this.envResolver = envResolver;
    }

    public AgentNetworkStatus status() {
        final List<AgentPeer> peers = peers();
        final int actionablePeers = (int) peers.stream()
            .filter(peer -> peer.enabled() && peer.allowTaskExecution())
            .count();
        return new AgentNetworkStatus(
            config.agent.enabled,
            isConfigured(),
            profile().configValue(),
            nodeId(),
            cluster(),
            bindHost(),
            port(),
            sharedSecretEnvVar(),
            hasSharedSecret(),
            peers.size(),
            actionablePeers,
            config.agent.shareBillingAcrossPeers
        );
    }

    public List<AgentPeer> peers() {
        final List<AgentPeer> peers = new ArrayList<>();
        for (final OpenCraftConfig.AgentPeerConfig peer : config.agent.peers) {
            if (peer == null) continue;
            peers.add(new AgentPeer(
                safe(peer.peerId),
                safe(peer.displayName),
                safe(peer.host),
                Math.max(1, peer.port),
                safe(peer.role),
                peer.enabled,
                peer.allowTaskExecution
            ));
        }
        return peers;
    }

    public Optional<AgentChallenge> issueOnboardingChallenge(final String candidatePeerId,
                                                             final List<String> scopes) {
        if (!config.agent.enabled || !profile().canCoordinateFleet() || nodeId().isBlank() || !hasSharedSecret()) {
            return Optional.empty();
        }
        final Optional<AgentPeer> peerOpt = findPeer(candidatePeerId);
        if (peerOpt.isEmpty()) {
            return Optional.empty();
        }
        final Instant issuedAt = clock.instant();
        final Instant expiresAt = issuedAt.plusSeconds(Math.max(15, config.agent.challengeTtlSeconds));
        final String challengeId = UUID.randomUUID().toString();
        final String phrase = challengePhraseService.createPhrase(
            sharedSecret(),
            challengeId,
            nodeId(),
            peerOpt.get().peerId()
        );
        return Optional.of(new AgentChallenge(
            challengeId,
            nodeId(),
            peerOpt.get().peerId(),
            phrase,
            issuedAt,
            expiresAt,
            scopes == null ? List.of() : List.copyOf(scopes)
        ));
    }

    public boolean verifyOnboardingResponse(final AgentChallenge challenge,
                                            final String responsePhrase,
                                            final Instant respondedAt) {
        if (challenge == null || responsePhrase == null || responsePhrase.isBlank() || !hasSharedSecret()) {
            return false;
        }
        final long skew = Math.max(0L, config.agent.allowedClockSkewSeconds);
        if (respondedAt.isBefore(challenge.issuedAt().minusSeconds(skew))) {
            return false;
        }
        if (respondedAt.isAfter(challenge.expiresAt().plusSeconds(skew))) {
            return false;
        }
        return challengePhraseService.matchesPhrase(
            sharedSecret(),
            challenge.challengeId(),
            challenge.initiatorNodeId(),
            challenge.candidatePeerId(),
            responsePhrase
        );
    }

    public FleetTaskEnvelope draftFleetTask(final String initiatedBy,
                                            final List<String> targetPeerIds) {
        final List<String> targets = dedupeTargets(targetPeerIds);
        return new FleetTaskEnvelope(
            UUID.randomUUID().toString(),
            safe(initiatedBy),
            nodeId(),
            targets,
            clock.instant(),
            config.agent.shareBillingAcrossPeers,
            estimateBillableRequestUnits(targets.size()),
            targets.size()
        );
    }

    public Optional<FleetRunSnapshot> createFleetRun(final String initiatedBy,
                                                     final String requestSummary,
                                                     final List<String> targetPeerIds) {
        if (!canCoordinateFleet()) return Optional.empty();
        final FleetTaskEnvelope envelope = draftFleetTask(initiatedBy, targetPeerIds);
        if (envelope.targetPeerIds().isEmpty()) return Optional.empty();
        if (!actionablePeerIds().containsAll(envelope.targetPeerIds())) {
            return Optional.empty();
        }
        return Optional.of(fleetRunService.createRun(
            safe(initiatedBy),
            nodeId(),
            profile().configValue(),
            safe(requestSummary),
            envelope.targetPeerIds(),
            envelope.sharedBillingEnabled(),
            envelope.billableRequestUnits()
        ));
    }

    public Optional<FleetRunSnapshot> startFleetRun(final String runId) {
        return canCoordinateFleet() ? fleetRunService.startRun(runId) : Optional.empty();
    }

    public Optional<FleetRunSnapshot> addFleetStep(final String runId,
                                                   final String targetPeerId,
                                                   final String commandId,
                                                   final String summary) {
        final String normalizedTargetPeerId = safe(targetPeerId).toLowerCase(Locale.ROOT);
        if (normalizedTargetPeerId.isBlank() || !actionablePeerIds().contains(normalizedTargetPeerId)) {
            return Optional.empty();
        }
        return canCoordinateFleet()
            ? fleetRunService.addStep(runId, normalizedTargetPeerId, safe(commandId), safe(summary))
            : Optional.empty();
    }

    public Optional<FleetRunSnapshot> updateFleetStepStatus(final String runId,
                                                            final String stepId,
                                                            final FleetStepStatus status,
                                                            final String detail) {
        return (canCoordinateFleet() || canAcceptFleetTasks())
            ? fleetRunService.updateStepStatus(runId, safe(stepId), status, safe(detail))
            : Optional.empty();
    }

    public Optional<FleetRunSnapshot> completeFleetRun(final String runId, final String detail) {
        return (canCoordinateFleet() || canAcceptFleetTasks())
            ? fleetRunService.completeRun(runId, safe(detail))
            : Optional.empty();
    }

    public Optional<FleetRunSnapshot> failFleetRun(final String runId, final String reason) {
        return (canCoordinateFleet() || canAcceptFleetTasks())
            ? fleetRunService.failRun(runId, safe(reason))
            : Optional.empty();
    }

    public Optional<FleetRunSnapshot> cancelFleetRun(final String runId, final String detail) {
        return (canCoordinateFleet() || canAcceptFleetTasks())
            ? fleetRunService.cancelRun(runId, safe(detail))
            : Optional.empty();
    }

    public Optional<FleetRunSnapshot> fleetRun(final String runId) {
        return fleetRunService.getRun(safe(runId));
    }

    public List<FleetRunSnapshot> recentFleetRuns(final int limit) {
        return fleetRunService.recentRuns(limit);
    }

    public int estimateBillableRequestUnits(final int targetCount) {
        final int normalized = Math.max(1, targetCount);
        return config.agent.shareBillingAcrossPeers ? 1 : normalized;
    }

    public boolean isConfigured() {
        return config.agent.enabled
            && !nodeId().isBlank()
            && !cluster().isBlank()
            && hasSharedSecret();
    }

    public NodeProfile profile() {
        return NodeProfile.fromString(config.profile);
    }

    public boolean canCoordinateFleet() {
        return config.agent.enabled && profile().canCoordinateFleet();
    }

    public boolean canAcceptFleetTasks() {
        return config.agent.enabled && profile().canAcceptFleetTasks();
    }

    public boolean hasSharedSecret() {
        final String secret = sharedSecret();
        return secret != null && !secret.isBlank();
    }

    public String sharedSecretEnvVar() {
        return safe(config.agent.sharedSecretEnvVar).isBlank()
            ? "OPENCRAFT_AGENT_SECRET"
            : config.agent.sharedSecretEnvVar.strip();
    }

    public String challengeFingerprint() {
        return hasSharedSecret() ? challengePhraseService.fingerprint(sharedSecret()) : "";
    }

    private Optional<AgentPeer> findPeer(final String candidatePeerId) {
        if (candidatePeerId == null || candidatePeerId.isBlank()) return Optional.empty();
        return peers().stream()
            .filter(peer -> peer.peerId().equalsIgnoreCase(candidatePeerId.strip()))
            .findFirst();
    }

    private Set<String> actionablePeerIds() {
        final LinkedHashSet<String> peerIds = new LinkedHashSet<>();
        for (final AgentPeer peer : peers()) {
            final String peerId = safe(peer.peerId()).toLowerCase(Locale.ROOT);
            if (!peerId.isBlank() && peer.enabled() && peer.allowTaskExecution()) {
                peerIds.add(peerId);
            }
        }
        return Set.copyOf(peerIds);
    }

    private String sharedSecret() {
        return envResolver.apply(sharedSecretEnvVar());
    }

    private String nodeId() {
        return safe(config.agent.nodeId);
    }

    private String cluster() {
        return safe(config.agent.cluster).isBlank() ? "default" : config.agent.cluster.strip();
    }

    private String bindHost() {
        return safe(config.agent.bindHost).isBlank() ? "0.0.0.0" : config.agent.bindHost.strip();
    }

    private int port() {
        return Math.max(1, config.agent.port);
    }

    private static String safe(final String value) {
        return value == null ? "" : value.strip();
    }

    private static List<String> dedupeTargets(final List<String> targetPeerIds) {
        if (targetPeerIds == null || targetPeerIds.isEmpty()) return List.of();
        final LinkedHashSet<String> set = new LinkedHashSet<>();
        for (final String target : targetPeerIds) {
            final String normalized = safe(target).toLowerCase(Locale.ROOT);
            if (!normalized.isBlank()) set.add(normalized);
        }
        return List.copyOf(set);
    }
}
