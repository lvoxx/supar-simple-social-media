package com.lvoxx.sssm.post_service.outbox;

import com.google.protobuf.Timestamp;
import com.lvoxx.sssm.event.v1.EngagementType;
import com.lvoxx.sssm.event.v1.PostCreated;
import com.lvoxx.sssm.event.v1.PostDeleted;
import com.lvoxx.sssm.event.v1.PostEngagement;
import com.lvoxx.sssm.post_service.domain.OutboxEvent;
import com.lvoxx.sssm.post_service.domain.Post;
import java.time.Instant;
import java.util.UUID;

/**
 * Builds {@link OutboxEvent} rows whose payload is a serialized Protobuf message from
 * {@code schemas/} — the single source of truth shared with the Go consumers. Centralizing the
 * proto-building here keeps the service layer free of wire-format details.
 */
public final class OutboxEventFactory {

    /** Outbox aggregate type for every post event. */
    public static final String AGGREGATE_TYPE = "post";

    public static final String POST_CREATED = "PostCreated";
    public static final String POST_DELETED = "PostDeleted";
    public static final String POST_ENGAGEMENT = "PostEngagement";

    private OutboxEventFactory() {
    }

    public static OutboxEvent postCreated(Post post) {
        PostCreated.Builder event = PostCreated.newBuilder()
                .setPostId(post.getId().toString())
                .setAuthorId(post.getAuthorId().toString())
                .setText(post.getText())
                .setCreatedAt(toTimestamp(post.getCreatedAt()));
        if (post.getReplyToPostId() != null) {
            event.setReplyToPostId(post.getReplyToPostId().toString());
        }
        return new OutboxEvent(
                AGGREGATE_TYPE, post.getId(), POST_CREATED, event.build().toByteArray());
    }

    public static OutboxEvent postDeleted(Post post, Instant deletedAt) {
        PostDeleted event = PostDeleted.newBuilder()
                .setPostId(post.getId().toString())
                .setAuthorId(post.getAuthorId().toString())
                .setDeletedAt(toTimestamp(deletedAt))
                .build();
        return new OutboxEvent(
                AGGREGATE_TYPE, post.getId(), POST_DELETED, event.toByteArray());
    }

    /**
     * Builds a {@link PostEngagement} event for an engagement add/remove (like/unlike,
     * repost/unrepost, bookmark/unbookmark). The aggregate is the post, so all post events share an
     * ordering key and topic.
     */
    public static OutboxEvent postEngagement(
            UUID postId, UUID actorId, EngagementType type, Instant occurredAt) {
        PostEngagement event = PostEngagement.newBuilder()
                .setPostId(postId.toString())
                .setActorId(actorId.toString())
                .setType(type)
                .setOccurredAt(toTimestamp(occurredAt))
                .build();
        return new OutboxEvent(AGGREGATE_TYPE, postId, POST_ENGAGEMENT, event.toByteArray());
    }

    private static Timestamp toTimestamp(Instant instant) {
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }
}
