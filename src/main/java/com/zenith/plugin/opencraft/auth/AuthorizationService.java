package com.zenith.plugin.opencraft.auth;

import com.zenith.plugin.opencraft.OpenCraftConfig;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.jspecify.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

public final class AuthorizationService {
    private static final Pattern STRIP_DASHES = Pattern.compile("-");

    private final OpenCraftConfig       config;
    private final ComponentLogger logger;

    public AuthorizationService(final OpenCraftConfig config, final ComponentLogger logger) {
        this.config = config;
        this.logger = logger;
    }

        public Optional<UserIdentity> resolveByUuid(final UUID uuid, final String username) {
        final String withDashes    = uuid.toString();
        final String withoutDashes = STRIP_DASHES.matcher(withDashes).replaceAll("");

        String roleStr = config.users.get(withDashes);
        if (roleStr == null) roleStr = config.users.get(withoutDashes);

        if (roleStr == null) {
            return Optional.empty();
        }

        final UserRole role = UserRole.fromString(roleStr);
        return Optional.of(new UserIdentity(uuid, username, role, true));
    }

        public Optional<UserIdentity> resolveByUsername(final String username) {
        if (!config.allowUsernameOnlyFallback) {
            return Optional.empty();
        }

        final String roleStr = config.users.get(username);
        if (roleStr == null) {
            return Optional.empty();
        }

        final UserRole configuredRole = UserRole.fromString(roleStr);
        final UserRole effectiveRole = (configuredRole == UserRole.ADMIN) ? UserRole.MEMBER : configuredRole;

        if (configuredRole == UserRole.ADMIN) {
            logger.warn("[OpenCraft] Username-only resolution for '{}': configured role is ADMIN " +
                "but effective role is MEMBER (UUID required for admin trust). " +
                "Set a UUID key in the users config to grant admin access.", username);
        }

        return Optional.of(new UserIdentity(null, username, effectiveRole, false));
    }

        public Optional<UserIdentity> resolve(@Nullable final UUID uuid, final String username) {
        if (uuid != null) {
            final Optional<UserIdentity> byUuid = resolveByUuid(uuid, username);
            if (byUuid.isPresent()) return byUuid;
            return Optional.empty();
        }
        return resolveByUsername(username);
    }
}
