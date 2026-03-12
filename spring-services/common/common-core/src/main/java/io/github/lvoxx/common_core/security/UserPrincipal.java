package io.github.lvoxx.common_core.security;

import java.util.Set;
import java.util.UUID;

import io.github.lvoxx.common_core.enums.UserRole;

/**
 * Immutable representation of the authenticated user.
 * Built from gateway-forwarded headers by security-starter.
 */
public record UserPrincipal(
        UUID userId,
        String username,
        Set<UserRole> roles,
        String ip) {

    public boolean isAdmin() {
        return roles.contains(UserRole.ADMIN);
    }

    public boolean isModerator() {
        return isAdmin() || roles.contains(UserRole.MODERATOR);
    }

    public boolean hasRole(UserRole role) {
        return roles.contains(role);
    }

    public boolean isSystem() {
        return roles.contains(UserRole.SYSTEM);
    }
}
