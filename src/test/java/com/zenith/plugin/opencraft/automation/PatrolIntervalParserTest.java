package com.zenith.plugin.opencraft.automation;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class PatrolIntervalParserTest {

    @Test
    void parsesSecondsMinutesHoursDaysAndTicks() {
        assertEquals(Duration.ofSeconds(30), PatrolIntervalParser.parse("30s"));
        assertEquals(Duration.ofMinutes(3), PatrolIntervalParser.parse("3m"));
        assertEquals(Duration.ofHours(2), PatrolIntervalParser.parse("2h"));
        assertEquals(Duration.ofDays(1), PatrolIntervalParser.parse("1d"));
        assertEquals(Duration.ofMillis(50), PatrolIntervalParser.parse("1t"));
    }

    @Test
    void parsesFractionalValues() {
        assertEquals(Duration.ofMinutes(90), PatrolIntervalParser.parse("1.5h"));
    }

    @Test
    void rejectsBadValues() {
        assertThrows(IllegalArgumentException.class, () -> PatrolIntervalParser.parse(""));
        assertThrows(IllegalArgumentException.class, () -> PatrolIntervalParser.parse("0s"));
        assertThrows(IllegalArgumentException.class, () -> PatrolIntervalParser.parse("abc"));
        assertThrows(IllegalArgumentException.class, () -> PatrolIntervalParser.parse("5w"));
    }
}
