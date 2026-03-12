package io.github.lvoxx.post_service.dto;

import java.time.Instant;
import java.util.UUID;

public record PostResponse(
                UUID id,
                UUID authorId,
                UUID groupId,
                String content,
                String postType,
                String status,
                String visibility,
                Boolean isEdited,
                Boolean isPinned,
                UUID replyToId,
                UUID repostOfId,
                Instant createdAt) {
}
