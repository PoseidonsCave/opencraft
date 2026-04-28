package com.zenith.plugin.opencraft.intent;

import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class IntentParserTest {

    private IntentParser parser;

    @BeforeEach
    void setUp() {
        parser = new IntentParser(mock(ComponentLogger.class));
    }

    // ── Plain response ────────────────────────────────────────────────────────

    @Test
    void parseValidPlainResponse() {
        final var result = parser.parse("{\"type\":\"response\",\"content\":\"Hello!\"}", "req-1");
        assertInstanceOf(IntentParser.PlainResponse.class, result);
        assertEquals("Hello!", ((IntentParser.PlainResponse) result).content());
    }

    @Test
    void parseMissingTypeDefaultsToResponse() {
        final var result = parser.parse("{\"content\":\"Hi there\"}", "req-2");
        assertInstanceOf(IntentParser.PlainResponse.class, result);
    }

    // ── Command intent ────────────────────────────────────────────────────────

    @Test
    void parseValidCommandIntent() {
        final String json = """
            {"type":"command_intent","command_id":"stash.scan","arguments":{},"explanation":"scan the stash"}
            """;
        final var result = parser.parse(json, "req-3");
        assertInstanceOf(IntentParser.CommandIntentResponse.class, result);
        final var ci = (IntentParser.CommandIntentResponse) result;
        assertEquals("stash.scan", ci.intent().commandId());
        assertEquals("scan the stash", ci.intent().explanation());
    }

    @Test
    void parseCommandIntentWithArguments() {
        final String json = """
            {"type":"command_intent","command_id":"stash.label","arguments":{"x":"100","y":"64","z":"-200"},"explanation":"label"}
            """;
        final var result = parser.parse(json, "req-4");
        assertInstanceOf(IntentParser.CommandIntentResponse.class, result);
        final var ci = (IntentParser.CommandIntentResponse) result;
        assertEquals("100", ci.intent().arguments().get("x"));
    }

    // ── Malformed JSON ────────────────────────────────────────────────────────

    @Test
    void parseMalformedJson_returnsPlainFallback() {
        final var result = parser.parse("{not valid json", "req-5");
        assertInstanceOf(IntentParser.PlainResponse.class, result);
        final String content = ((IntentParser.PlainResponse) result).content();
        assertFalse(content.isBlank());
    }

    @Test
    void parseEmptyString_returnsNoResponse() {
        final var result = parser.parse("", "req-6");
        assertInstanceOf(IntentParser.PlainResponse.class, result);
        assertEquals("(no response)", ((IntentParser.PlainResponse) result).content());
    }

    @Test
    void parseNull_returnsNoResponse() {
        final var result = parser.parse(null, "req-7");
        assertInstanceOf(IntentParser.PlainResponse.class, result);
    }

    // ── Prompt injection resistance ───────────────────────────────────────────

    @Test
    void colorCodesStripped() {
        final var result = parser.parse("{\"type\":\"response\",\"content\":\"§aHello §r§bWorld\"}", "req-8");
        assertInstanceOf(IntentParser.PlainResponse.class, result);
        final String content = ((IntentParser.PlainResponse) result).content();
        assertFalse(content.contains("§"), "Minecraft colour codes must be stripped");
        assertTrue(content.contains("Hello") && content.contains("World"));
    }

    @Test
    void commandIntentMissingCommandId_returnsPlainFallback() {
        final String json = "{\"type\":\"command_intent\",\"explanation\":\"test\"}";
        final var result = parser.parse(json, "req-9");
        assertInstanceOf(IntentParser.PlainResponse.class, result);
    }

    @Test
    void plainTextWithoutJson_treatedAsPlainResponse() {
        final var result = parser.parse("Just a plain text response.", "req-10");
        assertInstanceOf(IntentParser.PlainResponse.class, result);
        assertEquals("Just a plain text response.", ((IntentParser.PlainResponse) result).content());
    }
}
