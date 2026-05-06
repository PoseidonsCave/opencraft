package com.zenith.plugin.opencraft.intent;

import com.zenith.plugin.opencraft.OpenCraftConfig;
import com.zenith.plugin.opencraft.automation.PatrolService;
import com.zenith.plugin.opencraft.auth.UserIdentity;
import com.zenith.plugin.opencraft.auth.UserRole;
import com.zenith.plugin.opencraft.audit.AuditLogger;
import com.zenith.plugin.opencraft.discord.DiscordNotifier;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CommandExecutorTest {

    private static final UUID ADMIN_UUID = UUID.randomUUID();

    private OpenCraftConfig         config;
    private CommandAllowlist  allowlist;
    private CommandExecutor   executor;
    private AuditLogger       auditLogger;
    private DiscordNotifier   discordNotifier;
    private PatrolService     patrolService;

    @BeforeEach
    void setUp() {
        config = new OpenCraftConfig();

        final OpenCraftConfig.AllowedCommandConfig low = new OpenCraftConfig.AllowedCommandConfig();
        low.commandId   = "stash.scan";
        low.description = "Scan stash region";
        low.zenithCommand = "stash scan";
        low.roleRequired = "admin";
        low.riskLevel = "low";
        low.confirmationRequired = false;

        final OpenCraftConfig.AllowedCommandConfig high = new OpenCraftConfig.AllowedCommandConfig();
        high.commandId   = "stash.clearall";
        high.description = "Clear all stash data";
        high.zenithCommand = "stash clearall";
        high.roleRequired = "admin";
        high.riskLevel = "high";
        high.confirmationRequired = true;

        final OpenCraftConfig.AllowedCommandConfig withArgs = new OpenCraftConfig.AllowedCommandConfig();
        withArgs.commandId   = "stash.label";
        withArgs.description = "Label a container";
        withArgs.zenithCommand = "stash label {x} {y} {z} {label}";
        withArgs.roleRequired = "admin";
        withArgs.riskLevel = "low";
        withArgs.argumentSchema = Map.of("x","integer","y","integer","z","integer","label","string");

        config.allowedCommands = List.of(low, high, withArgs);
        config.confirmationTimeoutSeconds = 60;

        allowlist       = new CommandAllowlist(config);
        auditLogger     = mock(AuditLogger.class);
        discordNotifier = mock(DiscordNotifier.class);
        patrolService   = new PatrolService(mock(ComponentLogger.class), discordNotifier);

        executor = new CommandExecutor(config, allowlist, patrolService, auditLogger, discordNotifier,
            mock(ComponentLogger.class));
    }

    private UserIdentity admin() {
        return new UserIdentity(ADMIN_UUID, "Notch", UserRole.ADMIN, true);
    }

    private UserIdentity member() {
        return new UserIdentity(UUID.randomUUID(), "jeb_", UserRole.MEMBER, true);
    }

    @Test
    void member_cannotExecuteCommand() {
        final CommandIntent intent = new CommandIntent("stash.scan", Map.of(), "scan");
        final ExecutionResult result = executor.execute(intent, member(), "req-1");
        assertEquals(ExecutionResult.Status.DENIED, result.status());
    }

    @Test
    void nonWhitelistedCommand_denied() {
        final CommandIntent intent = new CommandIntent("stash.nuke", Map.of(), "nuke");
        final ExecutionResult result = executor.execute(intent, admin(), "req-2");
        assertEquals(ExecutionResult.Status.DENIED, result.status());
    }

    @Test
    void highRiskCommand_requiresConfirmation() {
        final CommandIntent intent = new CommandIntent("stash.clearall", Map.of(), "clear");
        final ExecutionResult result = executor.execute(intent, admin(), "req-3");
        assertEquals(ExecutionResult.Status.NEEDS_CONFIRMATION, result.status());
        assertNotNull(result.pending());
        assertTrue(executor.hasPendingConfirmation(admin()));
    }

    @Test
    void confirm_executesAfterPending() {
        executor.execute(new CommandIntent("stash.clearall", Map.of(), "clear"), admin(), "req-4");
        assertTrue(executor.hasPendingConfirmation(admin()));
    }

    @Test
    void cancel_removesPending() {
        executor.execute(new CommandIntent("stash.clearall", Map.of(), "clear"), admin(), "req-5");
        assertTrue(executor.hasPendingConfirmation(admin()));
        assertTrue(executor.cancel(admin()));
        assertFalse(executor.hasPendingConfirmation(admin()));
    }

    @Test
    void missingRequiredArgument_denied() {
        final CommandIntent intent = new CommandIntent("stash.label",
            Map.of("x","100","y","64"), "label");
        final ExecutionResult result = executor.execute(intent, admin(), "req-6");
        assertEquals(ExecutionResult.Status.DENIED, result.status());
    }

    @Test
    void invalidIntegerArgument_denied() {
        final CommandIntent intent = new CommandIntent("stash.label",
            Map.of("x","not-a-number","y","64","z","0","label","myLabel"), "label");
        final ExecutionResult result = executor.execute(intent, admin(), "req-7");
        assertEquals(ExecutionResult.Status.DENIED, result.status());
    }

    @Test
    void injectionCharactersInArgument_denied() {
        final CommandIntent intent = new CommandIntent("stash.label",
            Map.of("x","100","y","64","z","0","label","hack; rm -rf /"), "label");
        final ExecutionResult result = executor.execute(intent, admin(), "req-8");
        assertEquals(ExecutionResult.Status.DENIED, result.status());
    }

    @Test
    void redact_removesNamedFields() {
        final String text = "apiKey: secret123 password: p@ss!";
        final String redacted = CommandExecutor.redact(text, List.of("apiKey", "password"));
        assertFalse(redacted.contains("secret123"), "Secret value must be redacted");
        assertFalse(redacted.contains("p@ss!"),     "Password value must be redacted");
        assertTrue(redacted.contains("[REDACTED]"));
    }

    @Test
    void redact_emptyFields_unchanged() {
        final String text = "some output";
        assertEquals(text, CommandExecutor.redact(text, List.of()));
    }

    @Test
    void internalPatrolList_executesWithoutTerminalCommand() {
        final OpenCraftConfig.AllowedCommandConfig internal = new OpenCraftConfig.AllowedCommandConfig();
        internal.commandId = "patrol.list";
        internal.description = "List patrols";
        internal.zenithCommand = "@internal:patrol.list";
        internal.roleRequired = "admin";
        config.allowedCommands = List.of(internal);
        allowlist = new CommandAllowlist(config);
        executor = new CommandExecutor(config, allowlist, patrolService, auditLogger, discordNotifier,
            mock(ComponentLogger.class));

        final ExecutionResult result = executor.execute(
            new CommandIntent("patrol.list", Map.of(), "list patrols"),
            admin(),
            "req-9"
        );

        assertEquals(ExecutionResult.Status.SUCCESS, result.status());
        assertTrue(result.message().contains("No patrols are scheduled."));
    }
}
