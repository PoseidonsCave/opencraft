package com.zenith.plugin.opencraft.intent;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CommandIntentNormalizerBoundaryTest {

    @Test
    void leastDoesNotTriggerEastSubstringMatch() {
        final CommandIntent normalized = CommandIntentNormalizer.normalize(
            new CommandIntent("walk", Map.of(), "walk at least a bit"),
            "Can you walk at least 5 blocks?"
        );

        assertEquals("pathfinder.thisway", normalized.commandId());
        assertEquals("5", normalized.arguments().get("blocks"));
    }

    @Test
    void northeastDoesNotTriggerEastSubstringMatch() {
        final CommandIntent normalized = CommandIntentNormalizer.normalize(
            new CommandIntent("walk", Map.of(), "head northeast"),
            "Walk 5 blocks northeast"
        );

        assertEquals("pathfinder.thisway", normalized.commandId());
        assertEquals("5", normalized.arguments().get("blocks"));
        assertFalse(normalized.arguments().containsKey("direction"));
    }
}
