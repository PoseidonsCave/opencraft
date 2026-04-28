package com.zenith.plugin.opencraft.auth;

import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * Immutable resolved identity for an incoming request.
 * uuid: the player's Minecraft UUID, or null if unavailable.
 * username: display name used only for whisper routing and audit logs.
 * role: the resolved authorization role.
 * uuidConfirmed: true if the UUID came directly from a signed packet
 *   (most trusted); false if derived from a cache lookup or username-only
 *   resolution (less trusted).
 */
public record UserIdentity(
    @Nullable UUID uuid,
    String         username,
    UserRole       role,
    boolean        uuidConfirmed
) {
    /** Returns a compact audit-friendly string without leaking sensitive role info to untrusted contexts. */
    public String auditLabel() {
        final String uuidStr = uuid != null ? uuid.toString() : "no-uuid";
        return username + " (" + uuidStr + ")";
    }
}
