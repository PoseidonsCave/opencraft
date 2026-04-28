package com.zenith.plugin.opencraft.update;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PluginUpdateServiceTest {

    @Test
    void isNewer_candidateHigherPatch() {
        assertTrue(PluginUpdateService.isNewer("1.0.0", "1.0.1"));
    }

    @Test
    void isNewer_candidateHigherMinor() {
        assertTrue(PluginUpdateService.isNewer("1.0.0", "1.1.0"));
    }

    @Test
    void isNewer_candidateHigherMajor() {
        assertTrue(PluginUpdateService.isNewer("1.0.0", "2.0.0"));
    }

    @Test
    void isNewer_sameVersion_false() {
        assertFalse(PluginUpdateService.isNewer("1.0.0", "1.0.0"));
    }

    @Test
    void isNewer_candidateOlder_false() {
        assertFalse(PluginUpdateService.isNewer("1.2.0", "1.1.9"));
    }

    @Test
    void isNewer_preReleaseStripped() {
        // "1.0.1-beta.1" should be treated as 1.0.1 vs current 1.0.0
        assertTrue(PluginUpdateService.isNewer("1.0.0", "1.0.1-beta.1"));
    }
}
