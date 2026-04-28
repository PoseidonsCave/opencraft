package com.zenith.plugin.opencraft.execute;

import com.zenith.plugin.opencraft.OpenCraftConfig;
import com.zenith.plugin.opencraft.audit.AuditEvent;
import com.zenith.plugin.opencraft.audit.AuditLogger;
import com.zenith.plugin.opencraft.auth.UserIdentity;
import com.zenith.plugin.opencraft.intent.CommandExecutor;
import com.zenith.plugin.opencraft.intent.ExecutionResult;
import com.zenith.plugin.opencraft.plan.OperationalPlan;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static com.zenith.Globals.BARITONE;
import static com.zenith.Globals.EXECUTOR;

/**
 * Executes a multi-step OperationalPlan sequentially.
 *
 * Each step is validated and dispatched through CommandExecutor (which enforces
 * RBAC, allowlist, argument validation, and audit logging). This class adds
 * sequential coordination, pathfinder completion detection, per-user concurrency
 * limits, milestone notifications, and operation timeouts.
 *
 * Security invariants:
 *   - One active operation per user at a time.
 *   - Steps are validated by CommandExecutor — no security bypasses here.
 *   - operationsEnabled gate is checked at entry.
 *   - Pathfinder step completion is detected via BARITONE.isActive() polling;
 *     no external state injection is possible.
 *   - Timeouts cancel the operation gracefully.
 */
public final class OperationExecutor {

    private static final long POLL_INTERVAL_SECONDS = 2L;
    private static final String PATHFINDER_PREFIX    = "pathfinder.";

    private final OpenCraftConfig   config;
    private final CommandExecutor   commandExecutor;
    private final AuditLogger       auditLogger;
    private final ComponentLogger   logger;

    private final ConcurrentHashMap<UUID, ActiveOperation> activeOps  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, StagedOperation> stagedPlans = new ConcurrentHashMap<>();

    public OperationExecutor(
        final OpenCraftConfig config,
        final CommandExecutor commandExecutor,
        final AuditLogger auditLogger,
        final ComponentLogger logger
    ) {
        this.config          = config;
        this.commandExecutor = commandExecutor;
        this.auditLogger     = auditLogger;
        this.logger          = logger;
    }

    // ── Staging (confirmation gate) ────────────────────────────────────────

    /**
     * Stage a plan awaiting user confirmation. Call startStagedOperation() after
     * the user responds with "confirm", or clearStagedPlan() on "cancel".
     */
    public void stagePlan(final OperationalPlan plan, final UserIdentity identity) {
        if (identity.uuid() == null) return;
        final Instant expiresAt = Instant.now().plusSeconds(
            Math.max(1, config.operationConfirmationTimeoutSeconds)
        );
        stagedPlans.put(identity.uuid(), new StagedOperation(plan, expiresAt));
    }

    public boolean hasStagedPlan(final UserIdentity identity) {
        return getStagedPlan(identity) != null;
    }

    public boolean clearStagedPlan(final UserIdentity identity) {
        if (identity.uuid() == null) return false;
        return stagedPlans.remove(identity.uuid()) != null;
    }

    /**
     * Start executing the previously staged plan after user confirmation.
     * Returns null if no staged plan exists, otherwise the outcome of step 0.
     */
    public String startStagedOperation(
        final UserIdentity identity,
        final String requestId,
        final Consumer<String> whisperFn
    ) {
        if (identity.uuid() == null) return null;
        final StagedOperation staged = stagedPlans.remove(identity.uuid());
        if (staged == null) return null;
        if (staged.isExpired()) {
            return "Operation confirmation expired. Please re-submit the request.";
        }
        return startOperation(staged.plan(), identity, requestId, whisperFn);
    }

    // ── Execution ─────────────────────────────────────────────────────────

    /**
     * Start executing a plan. Returns a brief user-facing status string.
     * The whisperFn callback is used to send milestone updates on the calling
     * thread (initial) and from the scheduled polling thread (subsequent).
     */
    public String startOperation(
        final OperationalPlan plan,
        final UserIdentity identity,
        final String requestId,
        final Consumer<String> whisperFn
    ) {
        if (identity.uuid() == null) {
            return "[OC] Cannot run operation: user identity not confirmed.";
        }

        if (activeOps.containsKey(identity.uuid())) {
            return "[OC] An operation is already running. Send 'cancel' to stop it first.";
        }

        if (plan.steps().isEmpty()) {
            return "[OC] Plan has no steps to execute.";
        }

        if (plan.steps().size() > config.maxOperationSteps) {
            return "[OC] Plan exceeds the maximum of " + config.maxOperationSteps + " steps.";
        }

        final var op = new ActiveOperation(plan, identity, requestId, whisperFn);
        activeOps.put(identity.uuid(), op);

        auditLogger.log(AuditEvent.operationStarted(requestId, identity,
            plan.steps().size(), plan.risk()));

        whisperFn.accept("[OC] Starting " + plan.steps().size() + "-step operation"
            + " (est. " + plan.estimatedDuration() + ").");

        executeNextStep(op);
        return null;
    }

    public boolean cancelOperation(final UserIdentity identity) {
        if (identity.uuid() == null) return false;
        final ActiveOperation op = activeOps.remove(identity.uuid());
        if (op == null) return false;
        op.cancelPoll();
        // Stop pathfinder if it was driving this operation
        if (BARITONE.isActive()) {
            BARITONE.stop();
        }
        auditLogger.log(AuditEvent.operationCancelled(op.requestId, identity));
        return true;
    }

    public boolean hasActiveOperation(final UserIdentity identity) {
        return identity.uuid() != null && activeOps.containsKey(identity.uuid());
    }

    public boolean isAwaitingStepConfirmation(final UserIdentity identity) {
        if (identity.uuid() == null) return false;
        final ActiveOperation op = activeOps.get(identity.uuid());
        return op != null && op.awaitingStepConfirmation;
    }

    public String confirmStep(
        final UserIdentity identity,
        final String requestId,
        final Consumer<String> whisperFn
    ) {
        if (identity.uuid() == null) return null;
        final ActiveOperation op = activeOps.get(identity.uuid());
        if (op == null || !op.awaitingStepConfirmation) {
            return null;
        }

        final int stepIndex = op.currentStep.get();
        if (stepIndex >= op.plan.steps().size()) {
            activeOps.remove(identity.uuid());
            return "No pending operation step to confirm.";
        }

        final var step = op.plan.steps().get(stepIndex);
        final ExecutionResult result = commandExecutor.confirm(identity, requestId);
        op.awaitingStepConfirmation = false;
        whisperFn.accept(result.message());

        if (result.status() == ExecutionResult.Status.SUCCESS) {
            op.currentStep.incrementAndGet();
            if (step.commandId().startsWith(PATHFINDER_PREFIX) && !step.commandId().equals("pathfinder.stop")) {
                schedulePathfinderPoll(op);
            } else {
                executeNextStep(op);
            }
            return null;
        }

        activeOps.remove(identity.uuid());
        auditLogger.log(AuditEvent.operationFailed(op.requestId, op.identity,
            step.commandId(), result.message()));
        return null;
    }

    // ── Internal execution loop ────────────────────────────────────────────

    private void executeNextStep(final ActiveOperation op) {
        final int stepIndex = op.currentStep.get();

        if (stepIndex >= op.plan.steps().size()) {
            // All steps done
            activeOps.remove(op.identity.uuid());
            auditLogger.log(AuditEvent.operationCompleted(op.requestId, op.identity, stepIndex));
            op.whisperFn.accept("[OC] Operation complete (" + stepIndex + "/" + stepIndex + " steps).");
            return;
        }

        final var step = op.plan.steps().get(stepIndex);
        final int total = op.plan.steps().size();

        logger.info("[OpenCraft] Operation req={} executing step {}/{}: {}",
            op.requestId, stepIndex + 1, total, step.commandId());

        if (total > 2) {
            op.whisperFn.accept("[OC] Step " + (stepIndex + 1) + "/" + total
                + ": " + step.explanation());
        }

        final ExecutionResult result = commandExecutor.execute(step, op.identity, op.requestId);

        if (result.status() == ExecutionResult.Status.DENIED
                || result.status() == ExecutionResult.Status.FAILED) {
            activeOps.remove(op.identity.uuid());
            auditLogger.log(AuditEvent.operationFailed(op.requestId, op.identity,
                step.commandId(), result.message()));
            op.whisperFn.accept("[OC] Operation failed at step " + (stepIndex + 1)
                + ": " + result.message());
            return;
        }

        if (result.needsConfirmation()) {
            // High-risk single-step within a plan — pause for sub-confirmation
            op.awaitingStepConfirmation = true;
            op.whisperFn.accept("[OC] Operation paused at step " + (stepIndex + 1)
                + " — awaiting confirmation: " + result.message());
            return;
        }

        op.currentStep.incrementAndGet();

        // If this was a pathfinder step, poll for completion before next step
        if (step.commandId().startsWith(PATHFINDER_PREFIX) && !step.commandId().equals("pathfinder.stop")) {
            schedulePathfinderPoll(op);
        } else {
            // Non-pathfinder: proceed immediately
            executeNextStep(op);
        }
    }

    private void schedulePathfinderPoll(final ActiveOperation op) {
        final long timeoutMs = (long) config.operationStepTimeoutMinutes * 60_000L;
        final Instant deadline = Instant.now().plus(Duration.ofMillis(timeoutMs));

        final ScheduledFuture<?>[] futureRef = new ScheduledFuture<?>[1];
        futureRef[0] = EXECUTOR.scheduleAtFixedRate(() -> {
            try {
                if (!activeOps.containsKey(op.identity.uuid())) {
                    // Operation was cancelled
                    futureRef[0].cancel(false);
                    return;
                }

                if (Instant.now().isAfter(deadline)) {
                    futureRef[0].cancel(false);
                    activeOps.remove(op.identity.uuid());
                    auditLogger.log(AuditEvent.operationFailed(op.requestId, op.identity,
                        "pathfinder", "Step timed out after " + config.operationStepTimeoutMinutes + " minutes"));
                    op.whisperFn.accept("[OC] Operation timed out waiting for pathfinder to complete.");
                    if (BARITONE.isActive()) BARITONE.stop();
                    return;
                }

                if (!BARITONE.isActive()) {
                    // Pathfinder finished (completed or stopped)
                    futureRef[0].cancel(false);
                    op.whisperFn.accept("[OC] Navigation complete — moving to next step.");
                    executeNextStep(op);
                }
            } catch (final Exception e) {
                logger.warn("[OpenCraft] Pathfinder poll error in operation req={}: {}",
                    op.requestId, e.getMessage());
            }
        }, POLL_INTERVAL_SECONDS, POLL_INTERVAL_SECONDS, TimeUnit.SECONDS);

        op.setPollFuture(futureRef[0]);
    }

    private StagedOperation getStagedPlan(final UserIdentity identity) {
        if (identity.uuid() == null) return null;
        final StagedOperation staged = stagedPlans.get(identity.uuid());
        if (staged == null) return null;
        if (!staged.isExpired()) {
            return staged;
        }
        stagedPlans.remove(identity.uuid(), staged);
        return null;
    }

    // ── Inner types ────────────────────────────────────────────────────────

    private record StagedOperation(OperationalPlan plan, Instant expiresAt) {
        boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

    private static final class ActiveOperation {
        final OperationalPlan   plan;
        final UserIdentity      identity;
        final String            requestId;
        final Consumer<String>  whisperFn;
        final AtomicInteger     currentStep = new AtomicInteger(0);
        volatile boolean        awaitingStepConfirmation;
        private volatile ScheduledFuture<?> pollFuture;

        ActiveOperation(
            final OperationalPlan plan,
            final UserIdentity identity,
            final String requestId,
            final Consumer<String> whisperFn
        ) {
            this.plan      = plan;
            this.identity  = identity;
            this.requestId = requestId;
            this.whisperFn = whisperFn;
        }

        void setPollFuture(final ScheduledFuture<?> f) {
            this.pollFuture = f;
        }

        void cancelPoll() {
            final var f = this.pollFuture;
            if (f != null) f.cancel(false);
        }
    }
}
