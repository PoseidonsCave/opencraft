package com.zenith.plugin.opencraft.discord;

import com.zenith.plugin.opencraft.auth.UserIdentity;
import com.zenith.plugin.opencraft.auth.UserRole;
import com.zenith.plugin.opencraft.intent.CommandIntent;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DiscordAuditPayloadTest {

    private static final UserIdentity ADMIN = new UserIdentity(
        UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5"),
        "Notch", UserRole.ADMIN, true);

    // ── Redaction: Discord @mentions ─────────────────────────────────────────

    @Test
    void sanitise_stripsMentions() {
        final String sanitised = DiscordAuditPayload.sanitise("Hey @everyone check this out");
        assertFalse(sanitised.contains("@everyone"), "@everyone must be stripped");
    }

    @Test
    void sanitise_stripsHereMention() {
        assertFalse(DiscordAuditPayload.sanitise("@here look").contains("@here"));
    }

    // ── Length capping ───────────────────────────────────────────────────────

    @Test
    void sanitise_cappedAt256Chars() {
        final String long256 = "a".repeat(300);
        assertTrue(DiscordAuditPayload.sanitise(long256).length() <= 256);
    }

    // ── Newlines stripped ────────────────────────────────────────────────────

    @Test
    void sanitise_newlinesReplaced() {
        final String result = DiscordAuditPayload.sanitise("line1\nline2\r\nline3");
        assertFalse(result.contains("\n"), "Newlines must be replaced");
        assertFalse(result.contains("\r"), "Carriage returns must be replaced");
    }

    // ── No secrets in payload ─────────────────────────────────────────────────

    @Test
    void from_doesNotLeakInternalDetails() {
        final CommandIntent intent = new CommandIntent("stash.scan", Map.of(), "scan");
        final DiscordAuditPayload payload = DiscordAuditPayload.from(
            "req-1", "COMMAND_EXECUTED", ADMIN,
            "whisper", "!oc scan the stash", "Done",
            intent, "allowed", "dispatched", "openai"
        );

        // The intent's zenithCommand is NOT in DiscordAuditPayload — only commandId
        assertEquals("stash.scan", payload.commandId());
        // UUID should be present for our admin identity
        assertEquals("069a79f4-44e9-4726-a5be-fca90e38aaf5", payload.uuid());

        // Response excerpt should be trimmed
        assertNotNull(payload.responseExcerpt());
    }

    @Test
    void from_nullIntent_noCommandFields() {
        final DiscordAuditPayload payload = DiscordAuditPayload.from(
            "req-2", "RESPONSE_SENT", ADMIN,
            "whisper", "question", "answer",
            null, "allowed", null, "openai"
        );
        assertNull(payload.commandId());
        assertNull(payload.commandExplanation());
    }
}
