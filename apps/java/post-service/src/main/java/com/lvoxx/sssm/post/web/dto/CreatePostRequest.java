package com.lvoxx.sssm.post.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Body for creating a post. The author is taken from the gateway-forwarded identity, never from
 * this body. {@code replyToPostId} is set only when the post is a reply in a thread; it must
 * reference an existing post.
 */
public record CreatePostRequest(
        @NotBlank
        @Size(max = 280)
        String text,

        UUID replyToPostId) {
}
