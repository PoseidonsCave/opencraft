package com.zenith.plugin.opencraft.agent;

import java.util.Locale;

public enum NodeProfile {
    MANAGER,
    AGENT,
    HYBRID;

    public static NodeProfile fromString(final String value) {
        if (value == null || value.isBlank()) return MANAGER;
        try {
            return NodeProfile.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException ignored) {
            return MANAGER;
        }
    }

    public String configValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public boolean canCoordinateFleet() {
        return this == MANAGER || this == HYBRID;
    }

    public boolean canAcceptFleetTasks() {
        return this == AGENT || this == HYBRID;
    }
}
