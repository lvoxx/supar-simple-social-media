package com.lvoxx.sssm.user_service.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body for creating the caller's profile. The Keycloak identity is taken from the JWT, never from
 * this body. {@code username} is constrained to lowercase to satisfy the {@code username_lowercase}
 * DB check.
 */
public record CreateProfileRequest(
        @NotBlank
        @Size(min = 3, max = 30)
        @Pattern(regexp = "^[a-z0-9_]+$",
                message = "must be lowercase letters, digits or underscores")
        String username,

        @NotBlank
        @Size(max = 50)
        String displayName,

        @Size(max = 160)
        String bio,

        @Size(max = 512)
        String avatarUrl,

        @Size(max = 512)
        String bannerUrl,

        @Size(max = 60)
        String location,

        @Size(max = 120)
        String website) {
}
