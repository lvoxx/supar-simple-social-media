package io.github.lvoxx.post_service.dto;

import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record CreatePostRequest(
                @Size(max = 2000) String content,
                List<UUID> mediaIds,
                UUID replyToId,
                UUID repostOfId,
                UUID groupId,
                String visibility,
                String postType) {
}
