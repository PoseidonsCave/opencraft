package com.zenith.plugin.opencraft.agent;

import java.util.Locale;

public enum FleetStepStatus {
    PLANNED,
    DISPATCHED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED;

    public static FleetStepStatus fromString(final String value) {
        if (value == null || value.isBlank()) return PLANNED;
        try {
            return FleetStepStatus.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException ignored) {
            return PLANNED;
        }
    }

    public String configValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
