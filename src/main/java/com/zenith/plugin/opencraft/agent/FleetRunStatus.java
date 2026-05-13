package com.zenith.plugin.opencraft.agent;

import java.util.Locale;

public enum FleetRunStatus {
    DRAFT,
    ACTIVE,
    COMPLETED,
    FAILED,
    CANCELLED;

    public static FleetRunStatus fromString(final String value) {
        if (value == null || value.isBlank()) return DRAFT;
        try {
            return FleetRunStatus.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException ignored) {
            return DRAFT;
        }
    }

    public String configValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
