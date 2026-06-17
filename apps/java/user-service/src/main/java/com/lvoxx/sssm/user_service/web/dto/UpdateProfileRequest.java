package com.lvoxx.sssm.user_service.web.dto;

import jakarta.validation.constraints.Size;

/**
 * Body for updating the caller's profile. Every field is optional; a {@code null} field means
 * "leave unchanged". {@code username} is intentionally not updatable in the MVP.
 */
public record UpdateProfileRequest(
        @Size(min = 1, max = 50)
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
