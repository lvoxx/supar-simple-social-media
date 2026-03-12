package io.github.lvoxx.user_service.dto;

import java.time.Instant;
import java.util.UUID;

public record FollowRequestResponse(
        UUID id,
        UUID requesterId,
        String requesterUsername,
        String requesterAvatarUrl,
        String status,
        Instant createdAt) {
}
