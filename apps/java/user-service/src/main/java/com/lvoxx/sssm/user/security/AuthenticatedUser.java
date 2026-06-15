package com.lvoxx.sssm.user.security;

import java.util.List;
import java.util.UUID;

/**
 * The caller's identity as asserted by the gateway sidecar (which performed the actual token
 * validation) and forwarded via trusted {@code X-Auth-*} headers. This service never sees or
 * decodes the raw JWT.
 *
 * @param id       Keycloak user id (the original JWT {@code sub}); also the {@code keycloak_id}
 *                 stored on the profile
 * @param username Keycloak {@code preferred_username}, when forwarded (may be {@code null})
 * @param roles    realm roles forwarded by the gateway (without the {@code ROLE_} prefix)
 */
public record AuthenticatedUser(UUID id, String username, List<String> roles) {
}
