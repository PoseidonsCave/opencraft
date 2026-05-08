package com.zenith.plugin.opencraft.automation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CardinalMovementServiceTest {

    @Test
    void normalizesCompassDirections() {
        assertEquals("north", CardinalMovementService.normalize("north").label());
        assertEquals("south", CardinalMovementService.normalize("south").label());
        assertEquals("east", CardinalMovementService.normalize("east").label());
        assertEquals("west", CardinalMovementService.normalize("west").label());
    }

    @Test
    void normalizesAxisDirections() {
        assertEquals("west", CardinalMovementService.normalize("-x").label());
        assertEquals("north", CardinalMovementService.normalize("-z").label());
        assertEquals("east", CardinalMovementService.normalize("x+").label());
        assertEquals("south", CardinalMovementService.normalize("z").label());
    }

    @Test
    void rejectsUnknownDirections() {
        assertThrows(IllegalArgumentException.class, () -> CardinalMovementService.normalize("up"));
    }
}
