package com.lvoxx.sssm.user.web.dto;

import com.lvoxx.sssm.user.domain.Profile;
import java.time.Instant;
import java.util.UUID;

/**
 * Public view of a {@link Profile}. Deliberately omits {@code keycloakId} — the link to the
 * identity provider is internal and never exposed to API clients.
 */
public record ProfileResponse(
        UUID id,
        String username,
        String displayName,
        String bio,
        String avatarUrl,
        String bannerUrl,
        String location,
        String website,
        boolean verified,
        long followerCount,
        long followingCount,
        Instant createdAt) {

    public static ProfileResponse from(Profile p) {
        return new ProfileResponse(
                p.getId(),
                p.getUsername(),
                p.getDisplayName(),
                p.getBio(),
                p.getAvatarUrl(),
                p.getBannerUrl(),
                p.getLocation(),
                p.getWebsite(),
                p.isVerified(),
                p.getFollowerCount(),
                p.getFollowingCount(),
                p.getCreatedAt());
    }
}
