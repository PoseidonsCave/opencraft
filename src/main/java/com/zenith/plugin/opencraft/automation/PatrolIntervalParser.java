package com.zenith.plugin.opencraft.automation;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;

public final class PatrolIntervalParser {

    private static final Map<String, Double> UNIT_SECONDS = Map.of(
        "", 0.05d,
        "t", 0.05d,
        "s", 1.0d,
        "m", 60.0d,
        "h", 3600.0d,
        "d", 86400.0d
    );

    private PatrolIntervalParser() {}

    public static Duration parse(final String input) {
        if (input == null) {
            throw new IllegalArgumentException("Duration is required.");
        }
        final String value = input.strip().toLowerCase(Locale.ROOT);
        if (value.isBlank()) {
            throw new IllegalArgumentException("Duration is required.");
        }

        int splitIndex = 0;
        while (splitIndex < value.length()) {
            final char ch = value.charAt(splitIndex);
            if ((ch >= '0' && ch <= '9') || ch == '.') {
                splitIndex++;
                continue;
            }
            break;
        }
        if (splitIndex == 0) {
            throw new IllegalArgumentException("Duration must start with a number.");
        }

        final double amount;
        try {
            amount = Double.parseDouble(value.substring(0, splitIndex));
        } catch (final NumberFormatException e) {
            throw new IllegalArgumentException("Duration number is invalid.");
        }
        if (!(amount > 0.0d)) {
            throw new IllegalArgumentException("Duration must be greater than zero.");
        }

        final String unit = value.substring(splitIndex);
        final Double secondsPerUnit = UNIT_SECONDS.get(unit);
        if (secondsPerUnit == null) {
            throw new IllegalArgumentException("Duration unit must be one of: t, s, m, h, d.");
        }

        final long millis = Math.max(1L, Math.round(amount * secondsPerUnit * 1000.0d));
        return Duration.ofMillis(millis);
    }
}
