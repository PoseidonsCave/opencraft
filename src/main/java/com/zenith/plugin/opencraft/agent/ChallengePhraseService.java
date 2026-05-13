package com.zenith.plugin.opencraft.agent;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Locale;

public final class ChallengePhraseService {
    private static final String HMAC_ALG = "HmacSHA256";

    private static final String[] ADJECTIVES = {
        "amber", "brisk", "calm", "cinder", "clear", "cobalt", "ember", "faint",
        "fern", "flint", "frost", "gold", "harbor", "iron", "ivory", "jade",
        "lunar", "maple", "moss", "north", "oak", "onyx", "opal", "quiet",
        "river", "sable", "silver", "solar", "stone", "swift", "umbral", "winter"
    };

    private static final String[] NOUNS = {
        "anchor", "anvil", "arrow", "beacon", "bridge", "cedar", "comet", "delta",
        "falcon", "forge", "garden", "harbor", "helm", "hollow", "lantern", "meadow",
        "mesa", "monarch", "nexus", "orbit", "palisade", "pine", "quartz", "raven",
        "signal", "spruce", "summit", "thicket", "throne", "valley", "watch", "wharf"
    };

    private static final String[] VERBS = {
        "align", "arrive", "assist", "attune", "balance", "confirm", "connect", "echo",
        "enroll", "follow", "gather", "guard", "harvest", "join", "listen", "link",
        "mirror", "observe", "open", "orbit", "prepare", "relay", "return", "rendezvous",
        "report", "respond", "secure", "shadow", "signal", "stabilize", "support", "sync"
    };

    public String createPhrase(final String sharedSecret,
                               final String challengeId,
                               final String initiatorNodeId,
                               final String candidatePeerId) {
        final byte[] digest = digest(sharedSecret, challengeId + "|" + initiatorNodeId + "|" + candidatePeerId);
        return ADJECTIVES[index(digest, 0, ADJECTIVES.length)]
            + "-" + NOUNS[index(digest, 1, NOUNS.length)]
            + "-" + VERBS[index(digest, 2, VERBS.length)];
    }

    public boolean matchesPhrase(final String sharedSecret,
                                 final String challengeId,
                                 final String initiatorNodeId,
                                 final String candidatePeerId,
                                 final String responsePhrase) {
        if (responsePhrase == null || responsePhrase.isBlank()) return false;
        final String expected = createPhrase(sharedSecret, challengeId, initiatorNodeId, candidatePeerId);
        return expected.equals(normalizePhrase(responsePhrase));
    }

    public String fingerprint(final String sharedSecret) {
        final byte[] digest = digest(sharedSecret, "fingerprint");
        return HexFormat.of().formatHex(digest, 0, 6).toLowerCase(Locale.ROOT);
    }

    private static String normalizePhrase(final String phrase) {
        return phrase.strip().toLowerCase(Locale.ROOT);
    }

    private static int index(final byte[] digest, final int offset, final int bound) {
        return Byte.toUnsignedInt(digest[offset]) % bound;
    }

    private static byte[] digest(final String sharedSecret, final String payload) {
        try {
            final Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(new SecretKeySpec(sharedSecret.getBytes(StandardCharsets.UTF_8), HMAC_ALG));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (final Exception e) {
            throw new IllegalStateException("Unable to create challenge phrase digest", e);
        }
    }
}
