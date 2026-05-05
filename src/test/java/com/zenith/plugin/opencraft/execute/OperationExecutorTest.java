package com.zenith.plugin.opencraft.execute;

import com.zenith.plugin.opencraft.OpenCraftConfig;
import com.zenith.plugin.opencraft.audit.AuditLogger;
import com.zenith.plugin.opencraft.auth.UserIdentity;
import com.zenith.plugin.opencraft.auth.UserRole;
import com.zenith.plugin.opencraft.intent.CommandExecutor;
import com.zenith.plugin.opencraft.intent.CommandIntent;
import com.zenith.plugin.opencraft.intent.ExecutionResult;
import com.zenith.plugin.opencraft.intent.PendingConfirmation;
import com.zenith.plugin.opencraft.plan.OperationalPlan;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OperationExecutorTest {

    private OpenCraftConfig config;
    private CommandExecutor commandExecutor;
    private OperationExecutor executor;
    private UserIdentity admin;
    private List<String> messages;

    @BeforeEach
    void setUp() {
        config = new OpenCraftConfig();
        config.maxOperationSteps = 5;
        config.operationConfirmationTimeoutSeconds = 1;

        commandExecutor = mock(CommandExecutor.class);
        executor = new OperationExecutor(
            config,
            commandExecutor,
            mock(AuditLogger.class),
            mock(ComponentLogger.class)
        );
        admin = new UserIdentity(UUID.randomUUID(), "Notch", UserRole.ADMIN, true);
        messages = new ArrayList<>();
    }

    @Test
    void stagedPlanExpires() throws InterruptedException {
        executor.stagePlan(singleStepPlan(), admin);

        Thread.sleep(1100L);

        assertEquals(
            "Operation confirmation expired. Please re-submit the request.",
            executor.startStagedOperation(admin, "req-expired", messages::add)
        );
        assertFalse(executor.hasStagedPlan(admin));
        assertTrue(messages.isEmpty());
    }

    @Test
    void clearStagedPlanRemovesPendingApproval() {
        executor.stagePlan(singleStepPlan(), admin);

        assertTrue(executor.clearStagedPlan(admin));
        assertFalse(executor.hasStagedPlan(admin));
    }

    @Test
    void confirmStepResumesPausedOperation() {
        final CommandIntent firstStep = new CommandIntent("stash.clearall", Map.of(), "clear");
        final CommandIntent secondStep = new CommandIntent("stash.scan", Map.of(), "scan");
        final OperationalPlan plan = new OperationalPlan(
            List.of(firstStep, secondStep),
            "HIGH",
            true,
            0,
            "~30s",
            ""
        );

        when(commandExecutor.execute(firstStep, admin, "req-start"))
            .thenReturn(ExecutionResult.needsConfirmation(
                new PendingConfirmation(firstStep, null, Instant.now().plusSeconds(60)),
                "[OC] Confirm step 1"
            ));
        when(commandExecutor.confirm(admin, "req-confirm"))
            .thenReturn(ExecutionResult.success("[OC] Done step 1"));
        when(commandExecutor.execute(secondStep, admin, "req-start"))
            .thenReturn(ExecutionResult.success("[OC] Done step 2"));

        executor.startOperation(plan, admin, "req-start", messages::add);

        assertTrue(executor.hasActiveOperation(admin));
        assertTrue(executor.isAwaitingStepConfirmation(admin));

        assertNull(executor.confirmStep(admin, "req-confirm", messages::add));

        assertFalse(executor.hasActiveOperation(admin));
        assertFalse(executor.isAwaitingStepConfirmation(admin));
        assertTrue(messages.stream().anyMatch(m -> m.contains("awaiting confirmation")));
        assertTrue(messages.stream().anyMatch(m -> m.contains("Done step 1")));
        assertTrue(messages.stream().anyMatch(m -> m.contains("Operation complete")));

        verify(commandExecutor).execute(firstStep, admin, "req-start");
        verify(commandExecutor).confirm(admin, "req-confirm");
        verify(commandExecutor).execute(secondStep, admin, "req-start");
    }

    private OperationalPlan singleStepPlan() {
        return new OperationalPlan(
            List.of(new CommandIntent("stash.scan", Map.of(), "scan")),
            "MEDIUM",
            true,
            0,
            "~10s",
            ""
        );
    }
}
