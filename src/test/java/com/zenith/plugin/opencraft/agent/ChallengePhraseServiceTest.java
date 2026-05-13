package com.zenith.plugin.opencraft.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChallengePhraseServiceTest {

    private final ChallengePhraseService service = new ChallengePhraseService();

    @Test
    void phraseIsDeterministicForSameInputs() {
        final String phrase1 = service.createPhrase("secret-1", "challenge-1", "node-a", "node-b");
        final String phrase2 = service.createPhrase("secret-1", "challenge-1", "node-a", "node-b");

        assertEquals(phrase1, phrase2);
        assertTrue(phrase1.split("-").length == 3);
    }

    @Test
    void phraseVerificationRejectsTampering() {
        final String phrase = service.createPhrase("secret-1", "challenge-1", "node-a", "node-b");

        assertTrue(service.matchesPhrase("secret-1", "challenge-1", "node-a", "node-b", phrase));
        assertFalse(service.matchesPhrase("secret-1", "challenge-2", "node-a", "node-b", phrase));
        assertFalse(service.matchesPhrase("secret-1", "challenge-1", "node-a", "node-c", phrase));
    }
}
