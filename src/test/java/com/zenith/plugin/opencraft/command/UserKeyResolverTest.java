package com.zenith.plugin.opencraft.command;

import com.zenith.feature.api.ProfileData;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class UserKeyResolverTest {

    @Test
    void uuidInput_passesThroughWithoutLookup() {
        final UUID uuid = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
        final UserKeyResolver resolver = new UserKeyResolver(username -> {
            fail("Lookup should not run for UUID input");
            return Optional.empty();
        });

        final var result = resolver.resolveForStorage(uuid.toString(), true);

        assertTrue(result.valid());
        assertEquals(uuid.toString(), result.storageKey());
        assertFalse(result.resolvedUuidFromUsername());
    }

    @Test
    void noDashUuid_normalizesToDashedUuid() {
        final UserKeyResolver resolver = new UserKeyResolver(username -> Optional.empty());

        final var result = resolver.resolveForStorage("069a79f444e94726a5befca90e38aaf5", true);

        assertTrue(result.valid());
        assertEquals("069a79f4-44e9-4726-a5be-fca90e38aaf5", result.storageKey());
    }

    @Test
    void usernameInput_resolvesToUuidWhenLookupSucceeds() {
        final UUID uuid = UUID.fromString("61699b2e-d327-4a01-9f1e-0ea8c3f06bc6");
        final UserKeyResolver resolver = new UserKeyResolver(username ->
            Optional.of(new TestProfileData("jeb_", uuid)));

        final var result = resolver.resolveForStorage("jeb_", true);

        assertTrue(result.valid());
        assertEquals(uuid.toString(), result.storageKey());
        assertEquals("jeb_", result.resolvedUsername());
        assertTrue(result.resolvedUuidFromUsername());
    }

    @Test
    void memberUsername_fallsBackWhenLookupFails() {
        final UserKeyResolver resolver = new UserKeyResolver(username -> Optional.empty());

        final var result = resolver.resolveForStorage("Notch", false);

        assertTrue(result.valid());
        assertEquals("Notch", result.storageKey());
        assertFalse(result.resolvedUuidFromUsername());
    }

    @Test
    void adminUsername_requiresUuidWhenLookupFails() {
        final UserKeyResolver resolver = new UserKeyResolver(username -> Optional.empty());

        final var result = resolver.resolveForStorage("Notch", true);

        assertFalse(result.valid());
        assertNotNull(result.error());
        assertTrue(result.error().contains("Admin access requires a UUID-backed player profile."));
    }

    @Test
    void invalidInput_isRejected() {
        final UserKeyResolver resolver = new UserKeyResolver(username -> Optional.empty());

        final var result = resolver.resolveForStorage("bad key!", false);

        assertFalse(result.valid());
        assertEquals("Key must be a dashed UUID, a 32-character UUID, or a Minecraft username.", result.error());
    }

    private record TestProfileData(String name, UUID uuid) implements ProfileData {
    }
}
