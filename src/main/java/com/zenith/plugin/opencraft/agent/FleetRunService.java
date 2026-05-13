package com.zenith.plugin.opencraft.agent;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class FleetRunService {
    private final Clock clock;
    private final int maxRetainedRuns;
    private final ConcurrentHashMap<String, MutableRun> runs = new ConcurrentHashMap<>();
    private final Deque<String> recency = new ArrayDeque<>();
    private final Object indexLock = new Object();

    public FleetRunService(final int maxRetainedRuns) {
        this(Clock.systemUTC(), maxRetainedRuns);
    }

    FleetRunService(final Clock clock, final int maxRetainedRuns) {
        this.clock = clock;
        this.maxRetainedRuns = Math.max(10, maxRetainedRuns);
    }

    public FleetRunSnapshot createRun(final String initiatedBy,
                                      final String originNodeId,
                                      final String profile,
                                      final String requestSummary,
                                      final List<String> targetPeerIds,
                                      final boolean sharedBillingEnabled,
                                      final int billableRequestUnits) {
        final Instant now = clock.instant();
        final String runId = UUID.randomUUID().toString();
        final MutableRun run = new MutableRun(
            runId,
            initiatedBy,
            originNodeId,
            profile,
            requestSummary,
            now,
            now,
            FleetRunStatus.DRAFT,
            sharedBillingEnabled,
            Math.max(1, billableRequestUnits),
            List.copyOf(targetPeerIds)
        );
        run.appendEvent(FleetEventType.RUN_CREATED, null, null,
            "Fleet run created for " + targetPeerIds.size() + " peer(s).",
            Map.of("billableRequestUnits", String.valueOf(run.billableRequestUnits)));
        runs.put(runId, run);
        synchronized (indexLock) {
            recency.remove(runId);
            recency.addFirst(runId);
            trimToCapacity();
        }
        return run.snapshot();
    }

    public Optional<FleetRunSnapshot> startRun(final String runId) {
        return mutateRun(runId, run -> {
            ensureNotTerminal(run);
            if (run.status != FleetRunStatus.DRAFT) {
                throw new IllegalStateException("Fleet run can only be started from draft state.");
            }
            run.status = FleetRunStatus.ACTIVE;
            run.updatedAt = clock.instant();
            run.appendEvent(FleetEventType.RUN_STARTED, null, null, "Fleet run started.", Map.of());
        });
    }

    public Optional<FleetRunSnapshot> addStep(final String runId,
                                              final String targetPeerId,
                                              final String commandId,
                                              final String summary) {
        return mutateRun(runId, run -> {
            ensureNotTerminal(run);
            if (!run.targetPeerIds.contains(targetPeerId)) {
                throw new IllegalArgumentException("Target peer is not part of this fleet run: " + targetPeerId);
            }
            final Instant now = clock.instant();
            final String stepId = "step-" + (run.steps.size() + 1);
            final MutableStep step = new MutableStep(
                stepId,
                targetPeerId,
                commandId,
                summary,
                FleetStepStatus.PLANNED,
                now,
                now,
                ""
            );
            run.steps.put(stepId, step);
            run.updatedAt = now;
            run.appendEvent(FleetEventType.STEP_ADDED, stepId, targetPeerId,
                "Added step " + stepId + " for " + targetPeerId + ".",
                Map.of("commandId", commandId, "summary", summary));
        });
    }

    public Optional<FleetRunSnapshot> updateStepStatus(final String runId,
                                                       final String stepId,
                                                       final FleetStepStatus status,
                                                       final String detail) {
        return mutateRun(runId, run -> {
            ensureNotTerminal(run);
            final MutableStep step = run.steps.get(stepId);
            if (step == null) {
                throw new IllegalArgumentException("Unknown step: " + stepId);
            }
            step.status = status;
            step.updatedAt = clock.instant();
            step.detail = detail == null ? "" : detail.strip();
            run.updatedAt = step.updatedAt;
            run.appendEvent(FleetEventType.STEP_STATUS_CHANGED, stepId, step.targetPeerId,
                "Step " + stepId + " marked " + status.configValue() + ".",
                Map.of("commandId", step.commandId, "detail", step.detail));
        });
    }

    public Optional<FleetRunSnapshot> completeRun(final String runId, final String detail) {
        return mutateRun(runId, run -> {
            ensureNotTerminal(run);
            run.status = FleetRunStatus.COMPLETED;
            run.updatedAt = clock.instant();
            run.appendEvent(FleetEventType.RUN_COMPLETED, null, null,
                detail == null || detail.isBlank() ? "Fleet run completed." : detail.strip(),
                Map.of("steps", String.valueOf(run.steps.size())));
        });
    }

    public Optional<FleetRunSnapshot> failRun(final String runId, final String reason) {
        return mutateRun(runId, run -> {
            ensureNotTerminal(run);
            run.status = FleetRunStatus.FAILED;
            run.updatedAt = clock.instant();
            run.appendEvent(FleetEventType.RUN_FAILED, null, null,
                reason == null || reason.isBlank() ? "Fleet run failed." : reason.strip(),
                Map.of("steps", String.valueOf(run.steps.size())));
        });
    }

    public Optional<FleetRunSnapshot> cancelRun(final String runId, final String detail) {
        return mutateRun(runId, run -> {
            ensureNotTerminal(run);
            run.status = FleetRunStatus.CANCELLED;
            run.updatedAt = clock.instant();
            run.appendEvent(FleetEventType.RUN_CANCELLED, null, null,
                detail == null || detail.isBlank() ? "Fleet run cancelled." : detail.strip(),
                Map.of("steps", String.valueOf(run.steps.size())));
        });
    }

    public Optional<FleetRunSnapshot> getRun(final String runId) {
        final MutableRun run = runs.get(runId);
        return run == null ? Optional.empty() : Optional.of(run.snapshot());
    }

    public List<FleetRunSnapshot> recentRuns(final int limit) {
        final int safeLimit = Math.max(1, limit);
        final List<String> ids;
        synchronized (indexLock) {
            ids = recency.stream().limit(safeLimit).toList();
        }
        final List<FleetRunSnapshot> snapshots = new ArrayList<>();
        for (final String id : ids) {
            final MutableRun run = runs.get(id);
            if (run != null) snapshots.add(run.snapshot());
        }
        snapshots.sort(Comparator.comparing(FleetRunSnapshot::updatedAt).reversed());
        return snapshots;
    }

    private Optional<FleetRunSnapshot> mutateRun(final String runId,
                                                 final java.util.function.Consumer<MutableRun> mutator) {
        final MutableRun run = runs.get(runId);
        if (run == null) return Optional.empty();
        synchronized (run) {
            mutator.accept(run);
            return Optional.of(run.snapshot());
        }
    }

    private void trimToCapacity() {
        while (recency.size() > maxRetainedRuns) {
            final String removed = recency.removeLast();
            runs.remove(removed);
        }
    }

    private static void ensureNotTerminal(final MutableRun run) {
        if (run.status.isTerminal()) {
            throw new IllegalStateException("Fleet run is already in terminal state: " + run.status.configValue());
        }
    }

    private final class MutableRun {
        private final String runId;
        private final String initiatedBy;
        private final String originNodeId;
        private final String profile;
        private final String requestSummary;
        private final Instant createdAt;
        private Instant updatedAt;
        private FleetRunStatus status;
        private final boolean sharedBillingEnabled;
        private final int billableRequestUnits;
        private final List<String> targetPeerIds;
        private final LinkedHashMap<String, MutableStep> steps = new LinkedHashMap<>();
        private final List<FleetRunEvent> events = new ArrayList<>();
        private String lastEventId;

        private MutableRun(final String runId,
                           final String initiatedBy,
                           final String originNodeId,
                           final String profile,
                           final String requestSummary,
                           final Instant createdAt,
                           final Instant updatedAt,
                           final FleetRunStatus status,
                           final boolean sharedBillingEnabled,
                           final int billableRequestUnits,
                           final List<String> targetPeerIds) {
            this.runId = runId;
            this.initiatedBy = initiatedBy;
            this.originNodeId = originNodeId;
            this.profile = profile;
            this.requestSummary = requestSummary;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
            this.status = status;
            this.sharedBillingEnabled = sharedBillingEnabled;
            this.billableRequestUnits = billableRequestUnits;
            this.targetPeerIds = targetPeerIds;
        }

        private void appendEvent(final FleetEventType type,
                                 final String stepId,
                                 final String targetPeerId,
                                 final String message,
                                 final Map<String, String> details) {
            final String eventId = UUID.randomUUID().toString();
            events.add(new FleetRunEvent(
                eventId,
                lastEventId,
                clock.instant(),
                runId,
                type,
                stepId,
                targetPeerId,
                message,
                Map.copyOf(details)
            ));
            lastEventId = eventId;
        }

        private FleetRunSnapshot snapshot() {
            final List<FleetStepSnapshot> stepSnapshots = steps.values().stream()
                .map(MutableStep::snapshot)
                .toList();
            return new FleetRunSnapshot(
                runId,
                initiatedBy,
                originNodeId,
                profile,
                requestSummary,
                createdAt,
                updatedAt,
                status,
                sharedBillingEnabled,
                billableRequestUnits,
                targetPeerIds,
                stepSnapshots,
                List.copyOf(events)
            );
        }
    }

    private static final class MutableStep {
        private final String stepId;
        private final String targetPeerId;
        private final String commandId;
        private final String summary;
        private final Instant createdAt;
        private FleetStepStatus status;
        private Instant updatedAt;
        private String detail;

        private MutableStep(final String stepId,
                            final String targetPeerId,
                            final String commandId,
                            final String summary,
                            final FleetStepStatus status,
                            final Instant createdAt,
                            final Instant updatedAt,
                            final String detail) {
            this.stepId = stepId;
            this.targetPeerId = targetPeerId;
            this.commandId = commandId;
            this.summary = summary;
            this.status = status;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
            this.detail = detail;
        }

        private FleetStepSnapshot snapshot() {
            return new FleetStepSnapshot(
                stepId,
                targetPeerId,
                commandId,
                summary,
                status,
                createdAt,
                updatedAt,
                detail
            );
        }
    }
}
