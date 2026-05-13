package com.zenith.plugin.opencraft.intent;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CommandIntentNormalizerTest {

    @Test
    void walkAliasWithRequestText_normalizesToPathfinderCardinal() {
        final CommandIntent normalized = CommandIntentNormalizer.normalize(
            new CommandIntent("walk", Map.of(), "walk south"),
            "Can you walk 5 blocks south from your current position?"
        );

        assertEquals("pathfinder.cardinal", normalized.commandId());
        assertEquals("south", normalized.arguments().get("direction"));
        assertEquals("5", normalized.arguments().get("blocks"));
    }

    @Test
    void moveAliasWithCoordinates_normalizesToGotoXz() {
        final CommandIntent normalized = CommandIntentNormalizer.normalize(
            new CommandIntent("move", Map.of("x", "100", "z", "-200"), "go there"),
            null
        );

        assertEquals("pathfinder.goto.xz", normalized.commandId());
        assertEquals("100", normalized.arguments().get("x"));
        assertEquals("-200", normalized.arguments().get("z"));
    }
}
