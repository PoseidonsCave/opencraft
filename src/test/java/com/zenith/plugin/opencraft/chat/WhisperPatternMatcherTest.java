package com.zenith.plugin.opencraft.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class WhisperPatternMatcherTest {

    private final WhisperPatternMatcher matcher = new WhisperPatternMatcher();

    @Test
    void matchesConfiguredPattern() {
        final var match = matcher.match("Notch whispers to you: !oc hello", "^(\\S+) whispers to you: (.+)$");

        assertNotNull(match);
        assertEquals("Notch", match.senderName());
        assertEquals("!oc hello", match.rawMessage());
    }

    @Test
    void matchesFromFormatFallback() {
        final var match = matcher.match("from Notch: !oc hello", "^this will not match$");

        assertNotNull(match);
        assertEquals("Notch", match.senderName());
        assertEquals("!oc hello", match.rawMessage());
    }

    @Test
    void matchesTellsYouFallback() {
        final var match = matcher.match("Notch tells you: !oc hello", "^this will not match$");

        assertNotNull(match);
        assertEquals("Notch", match.senderName());
        assertEquals("!oc hello", match.rawMessage());
    }

    @Test
    void returnsNullWhenNoPatternMatches() {
        final var match = matcher.match("to Plutonist: !oc hello", "^this will not match$");

        assertNull(match);
    }
}
