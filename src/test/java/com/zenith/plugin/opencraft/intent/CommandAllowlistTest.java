package com.zenith.plugin.opencraft.intent;

import com.zenith.plugin.opencraft.OpenCraftConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class CommandAllowlistTest {

    private OpenCraftConfig configWith(final String... ids) {
        final OpenCraftConfig cfg = new OpenCraftConfig();
        cfg.allowedCommands = new java.util.ArrayList<>();
        for (final String id : ids) {
            final OpenCraftConfig.AllowedCommandConfig c = new OpenCraftConfig.AllowedCommandConfig();
            c.commandId    = id;
            c.description  = "desc-" + id;
            c.zenithCommand = "cmd " + id;
            c.roleRequired = "admin";
            cfg.allowedCommands.add(c);
        }
        return cfg;
    }

    @Test
    void find_knownCommand_present() {
        final CommandAllowlist list = new CommandAllowlist(configWith("stash.scan", "stash.export"));
        assertTrue(list.find("stash.scan").isPresent());
        assertTrue(list.find("stash.export").isPresent());
    }

    @Test
    void find_unknownCommand_empty() {
        final CommandAllowlist list = new CommandAllowlist(configWith("stash.scan"));
        assertTrue(list.find("stash.nuke").isEmpty(), "Unknown command must not be found");
        assertTrue(list.find("").isEmpty());
        assertTrue(list.find(null).isEmpty());
    }

    @Test
    void emptyConfig_isEmpty() {
        final OpenCraftConfig cfg = new OpenCraftConfig();
        cfg.allowedCommands = List.of();
        final CommandAllowlist list = new CommandAllowlist(cfg);
        assertTrue(list.isEmpty());
    }

    @Test
    void commandsMissingZenithCommand_filtered() {
        final OpenCraftConfig cfg = new OpenCraftConfig();
        final OpenCraftConfig.AllowedCommandConfig c = new OpenCraftConfig.AllowedCommandConfig();
        c.commandId = "stash.scan";
        c.zenithCommand = ""; // invalid — must be filtered
        cfg.allowedCommands = List.of(c);
        final CommandAllowlist list = new CommandAllowlist(cfg);
        assertTrue(list.isEmpty(), "Commands with blank zenithCommand must be filtered");
    }

    @Test
    void adminToolDescriptions_doNotExposeInternals() {
        final CommandAllowlist list = new CommandAllowlist(configWith("stash.scan"));
        final var tools = list.getAdminToolDescriptions();
        assertEquals(1, tools.size());
        assertEquals("stash.scan", tools.get(0).commandId());
        assertTrue(tools.get(0).description().startsWith("desc-"));
    }

    @Test
    void denyByDefault_inventedCommandId_notFound() {
        final CommandAllowlist list = new CommandAllowlist(configWith("stash.scan"));
        assertFalse(list.find("execute_shell_command").isPresent());
        assertFalse(list.find("stash.scan ").isPresent()); // trailing space
        assertFalse(list.find("STASH.SCAN").isPresent()); // case-sensitive
    }
}
