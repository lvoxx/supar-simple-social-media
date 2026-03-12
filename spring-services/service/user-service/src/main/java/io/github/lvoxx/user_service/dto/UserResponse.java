package io.github.lvoxx.user_service.dto;

import java.time.Instant;
import java.util.UUID;

public record UserResponse(
                UUID id,
                String username,
                String displayName,
                String bio,
                String avatarUrl,
                String backgroundUrl,
                String websiteUrl,
                String location,
                Boolean isVerified,
                Boolean isPrivate,
                Integer followerCount,
                Integer followingCount,
                Integer postCount,
                String role,
                Instant createdAt) {
}
