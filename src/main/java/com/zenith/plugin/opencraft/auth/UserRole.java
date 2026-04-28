package com.zenith.plugin.opencraft.auth;

/**
 * Two-tier role hierarchy.
 * 
 *   - MEMBER — may ask general LLM questions.
 *   - ADMIN  — may ask general questions and request approved command execution.
 * 
 */
public enum UserRole {
    MEMBER,
    ADMIN;

    /** Returns true if this role meets or exceeds required. */
    public boolean satisfies(final UserRole required) {
        return this.ordinal() >= required.ordinal();
    }

    /**
     * Parse a role string from config; returns MEMBER for unrecognised values
     * so that unknown roles fail safe (least privilege).
     */
    public static UserRole fromString(final String s) {
        if (s == null) return MEMBER;
        return switch (s.trim().toLowerCase()) {
            case "admin" -> ADMIN;
            default      -> MEMBER;
        };
    }
}
