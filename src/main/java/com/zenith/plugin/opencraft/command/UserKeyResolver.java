package com.zenith.plugin.opencraft.command;

import com.zenith.feature.api.ProfileData;
import org.jspecify.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

final class UserKeyResolver {

    private static final Pattern MC_USERNAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final Pattern UUID_NO_DASHES = Pattern.compile("[0-9a-fA-F]{32}");

    private final Function<String, Optional<ProfileData>> profileLookup;

    UserKeyResolver(final Function<String, Optional<ProfileData>> profileLookup) {
        this.profileLookup = profileLookup;
    }

    Resolution resolveForStorage(final String rawKey, final boolean requireUuid) {
        final String normalizedKey = normalizeUserKey(rawKey);
        if (normalizedKey == null) {
            return Resolution.invalid("Key must be a dashed UUID, a 32-character UUID, or a Minecraft username.");
        }

        if (isUuidKey(normalizedKey)) {
            return Resolution.uuid(normalizedKey);
        }

        final Optional<ProfileData> profile = profileLookup.apply(normalizedKey);
        if (profile.isPresent() && profile.get().uuid() != null) {
            final String resolvedName = profile.get().name() == null || profile.get().name().isBlank()
                ? normalizedKey
                : profile.get().name();
            return Resolution.uuidFromUsername(profile.get().uuid().toString(), normalizedKey, resolvedName);
        }

        if (requireUuid) {
            return Resolution.invalid("Unable to resolve username '" + normalizedKey
                + "' to a UUID. Admin access requires a UUID-backed player profile.");
        }

        return Resolution.username(normalizedKey);
    }

    static boolean isUuidKey(final String key) {
        try {
            UUID.fromString(key);
            return true;
        } catch (final IllegalArgumentException e) {
            return false;
        }
    }

    @Nullable
    static String normalizeUserKey(final String rawKey) {
        final String key = rawKey == null ? "" : rawKey.strip();
        if (key.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(key).toString();
        } catch (final IllegalArgumentException ignored) {
        }

        if (UUID_NO_DASHES.matcher(key).matches()) {
            final String dashed = key.replaceFirst(
                "([0-9a-fA-F]{8})([0-9a-fA-F]{4})([0-9a-fA-F]{4})([0-9a-fA-F]{4})([0-9a-fA-F]{12})",
                "$1-$2-$3-$4-$5"
            );
            return UUID.fromString(dashed).toString();
        }

        return MC_USERNAME.matcher(key).matches() ? key : null;
    }

    record Resolution(
        @Nullable String storageKey,
        @Nullable String inputUsername,
        @Nullable String resolvedUsername,
        boolean resolvedUuidFromUsername,
        @Nullable String error
    ) {
        static Resolution invalid(final String error) {
            return new Resolution(null, null, null, false, error);
        }

        static Resolution uuid(final String storageKey) {
            return new Resolution(storageKey, null, null, false, null);
        }

        static Resolution uuidFromUsername(final String storageKey,
                                           final String inputUsername,
                                           final String resolvedUsername) {
            return new Resolution(storageKey, inputUsername, resolvedUsername, true, null);
        }

        static Resolution username(final String username) {
            return new Resolution(username, username, username, false, null);
        }

        boolean valid() {
            return storageKey != null && error == null;
        }
    }
}
