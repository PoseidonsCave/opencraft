package com.zenith.plugin.opencraft.auth;

import com.zenith.plugin.opencraft.OpenCraftConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthorizationServiceTest {

    private static final UUID ADMIN_UUID  = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
    private static final UUID MEMBER_UUID = UUID.fromString("61699b2e-d327-4a01-9f1e-0ea8c3f06bc6");
    private static final UUID UNKNOWN_UUID = UUID.randomUUID();

    private OpenCraftConfig config;
    private AuthorizationService service;

    @BeforeEach
    void setUp() {
        config = new OpenCraftConfig();
        config.users = Map.of(
            ADMIN_UUID.toString(), "admin",
            MEMBER_UUID.toString().replace("-", ""), "member"  // no-dash variant
        );
        config.allowUsernameOnlyFallback = false;
        service = new AuthorizationService(config, mock(net.kyori.adventure.text.logger.slf4j.ComponentLogger.class));
    }

    // ── UUID resolution ───────────────────────────────────────────────────────

    @Test
    void resolveAdmin_byUuid_withDashes() {
        final Optional<UserIdentity> result = service.resolveByUuid(ADMIN_UUID, "Notch");
        assertTrue(result.isPresent());
        assertEquals(UserRole.ADMIN, result.get().role());
        assertTrue(result.get().uuidConfirmed());
        assertEquals("Notch", result.get().username());
    }

    @Test
    void resolveMember_byUuid_withoutDashes() {
        final Optional<UserIdentity> result = service.resolveByUuid(MEMBER_UUID, "jeb_");
        assertTrue(result.isPresent());
        assertEquals(UserRole.MEMBER, result.get().role());
    }

    @Test
    void unknownUuid_returnsEmpty() {
        final Optional<UserIdentity> result = service.resolveByUuid(UNKNOWN_UUID, "unknown");
        assertTrue(result.isEmpty());
    }

    // ── Username fallback disabled ────────────────────────────────────────────

    @Test
    void username_fallbackDisabled_returnsEmpty() {
        config.allowUsernameOnlyFallback = false;
        final Optional<UserIdentity> result = service.resolveByUsername("Notch");
        assertTrue(result.isEmpty());
    }

    // ── Username fallback enabled ─────────────────────────────────────────────

    @Test
    void username_fallbackEnabled_member_granted() {
        config.allowUsernameOnlyFallback = true;
        config.users = Map.of("jeb_", "member");
        service = new AuthorizationService(config, mock(net.kyori.adventure.text.logger.slf4j.ComponentLogger.class));

        final Optional<UserIdentity> result = service.resolveByUsername("jeb_");
        assertTrue(result.isPresent());
        assertEquals(UserRole.MEMBER, result.get().role());
        assertFalse(result.get().uuidConfirmed());
        assertNull(result.get().uuid());
    }

    @Test
    void username_fallbackEnabled_adminDowngradedToMember() {
        // CRITICAL: admin must not be granted through username-only path
        config.allowUsernameOnlyFallback = true;
        config.users = Map.of("Notch", "admin");
        service = new AuthorizationService(config, mock(net.kyori.adventure.text.logger.slf4j.ComponentLogger.class));

        final Optional<UserIdentity> result = service.resolveByUsername("Notch");
        assertTrue(result.isPresent());
        assertEquals(UserRole.MEMBER, result.get().role(),
            "Admin must be downgraded to MEMBER when UUID is not confirmed");
    }

    // ── UUID present but not whitelisted ─────────────────────────────────────

    @Test
    void uuidPresentButNotWhitelisted_doesNotFallThroughToUsername() {
        // Even if username happens to match a user entry, UUID path must not
        // fall through to username lookup (prevents UUID spoofing from cracked server)
        config.users = Map.of(
            "Notch", "admin"  // username key only
        );
        service = new AuthorizationService(config, mock(net.kyori.adventure.text.logger.slf4j.ComponentLogger.class));

        // UUID not in whitelist → should be denied even though username matches
        final Optional<UserIdentity> result = service.resolve(UUID.randomUUID(), "Notch");
        assertTrue(result.isEmpty());
    }

    // ── UserRole ──────────────────────────────────────────────────────────────

    @Test
    void userRole_satisfies() {
        assertTrue(UserRole.ADMIN.satisfies(UserRole.MEMBER));
        assertTrue(UserRole.ADMIN.satisfies(UserRole.ADMIN));
        assertTrue(UserRole.MEMBER.satisfies(UserRole.MEMBER));
        assertFalse(UserRole.MEMBER.satisfies(UserRole.ADMIN));
    }

    @Test
    void userRole_fromString_unknownDefaultsMember() {
        assertEquals(UserRole.MEMBER, UserRole.fromString("superadmin"));
        assertEquals(UserRole.MEMBER, UserRole.fromString(null));
        assertEquals(UserRole.MEMBER, UserRole.fromString(""));
        assertEquals(UserRole.ADMIN,  UserRole.fromString("ADMIN"));
        assertEquals(UserRole.ADMIN,  UserRole.fromString("admin"));
    }
}
