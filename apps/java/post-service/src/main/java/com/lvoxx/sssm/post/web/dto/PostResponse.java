package com.lvoxx.sssm.post.web.dto;

import com.lvoxx.sssm.post.domain.Post;
import java.time.Instant;
import java.util.UUID;

/**
 * Public view of a {@link Post}, including the denormalized engagement counts.
 */
public record PostResponse(
        UUID id,
        UUID authorId,
        String text,
        UUID replyToPostId,
        long replyCount,
        long likeCount,
        long repostCount,
        long bookmarkCount,
        Instant createdAt) {

    public static PostResponse from(Post p) {
        return new PostResponse(
                p.getId(),
                p.getAuthorId(),
                p.getText(),
                p.getReplyToPostId(),
                p.getReplyCount(),
                p.getLikeCount(),
                p.getRepostCount(),
                p.getBookmarkCount(),
                p.getCreatedAt());
    }
}
