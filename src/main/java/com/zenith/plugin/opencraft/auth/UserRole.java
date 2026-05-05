package com.zenith.plugin.opencraft.auth;

public enum UserRole {
    MEMBER,
    ADMIN;

        public boolean satisfies(final UserRole required) {
        return this.ordinal() >= required.ordinal();
    }

        public static UserRole fromString(final String s) {
        if (s == null) return MEMBER;
        return switch (s.trim().toLowerCase()) {
            case "admin" -> ADMIN;
            default      -> MEMBER;
        };
    }
}
