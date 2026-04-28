package com.zenith.plugin.opencraft.auth;

import com.zenith.plugin.opencraft.OpenCraftConfig;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.jspecify.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Resolves incoming Minecraft player identities against the operator-configured
 * user table and returns the appropriate UserIdentity.
 *
 * Trust precedence (highest to lowest):
 * 
 *   - UUID match from a signed ClientboundPlayerChatPacket (uuidConfirmed=true).
 *   - UUID match from a server-side cache lookup (uuidConfirmed=false).
 *   - Username match when allowUsernameOnlyFallback=true;
 *       admin role is NEVER granted through username-only resolution.
 * 
 *
 * Non-whitelisted users always receive Optional#empty(), and the
 * caller is responsible for silently ignoring the request.
 */
public final class AuthorizationService {

    // Minecraft UUIDs normalised without dashes for config-key comparison.
    private static final Pattern STRIP_DASHES = Pattern.compile("-");

    private final OpenCraftConfig       config;
    private final ComponentLogger logger;

    public AuthorizationService(final OpenCraftConfig config, final ComponentLogger logger) {
        this.config = config;
        this.logger = logger;
    }

    /**
     * Resolve identity when a UUID is directly available (preferred path — player chat packets).
     */
    public Optional<UserIdentity> resolveByUuid(final UUID uuid, final String username) {
        final String withDashes    = uuid.toString();
        final String withoutDashes = STRIP_DASHES.matcher(withDashes).replaceAll("");

        String roleStr = config.users.get(withDashes);
        if (roleStr == null) roleStr = config.users.get(withoutDashes);

        if (roleStr == null) {
            // Not whitelisted — deny silently
            return Optional.empty();
        }

        final UserRole role = UserRole.fromString(roleStr);
        return Optional.of(new UserIdentity(uuid, username, role, true));
    }

    /**
     * Resolve identity by username only (used when UUID is not available from the packet).
     * Admin role is NEVER granted through this path regardless of config.
     */
    public Optional<UserIdentity> resolveByUsername(final String username) {
        if (!config.allowUsernameOnlyFallback) {
            return Optional.empty();
        }

        final String roleStr = config.users.get(username);
        if (roleStr == null) {
            return Optional.empty();
        }

        final UserRole configuredRole = UserRole.fromString(roleStr);
        // Downgrade: admin is never granted via username-only path (A07 mitigation)
        final UserRole effectiveRole = (configuredRole == UserRole.ADMIN) ? UserRole.MEMBER : configuredRole;

        if (configuredRole == UserRole.ADMIN) {
            logger.warn("[OpenCraft] Username-only resolution for '{}': configured role is ADMIN " +
                "but effective role is MEMBER (UUID required for admin trust). " +
                "Set a UUID key in the users config to grant admin access.", username);
        }

        return Optional.of(new UserIdentity(null, username, effectiveRole, false));
    }

    /**
     * Convenience resolver: tries UUID first, falls back to username if permitted.
     */
    public Optional<UserIdentity> resolve(@Nullable final UUID uuid, final String username) {
        if (uuid != null) {
            final Optional<UserIdentity> byUuid = resolveByUuid(uuid, username);
            if (byUuid.isPresent()) return byUuid;
            // UUID present but not in whitelist — do NOT fall through to username lookup
            // (could allow impersonation if username happens to match a whitelisted entry)
            return Optional.empty();
        }
        return resolveByUsername(username);
    }
}
