package com.zenith.plugin.opencraft.auth;

import org.jspecify.annotations.Nullable;

import java.util.UUID;

public record UserIdentity(
    @Nullable UUID uuid,
    String         username,
    UserRole       role,
    boolean        uuidConfirmed
) {
        public String auditLabel() {
        final String uuidStr = uuid != null ? uuid.toString() : "no-uuid";
        return username + " (" + uuidStr + ")";
    }
}
