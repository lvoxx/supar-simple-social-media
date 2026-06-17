package com.lvoxx.sssm.post_service.service;

import com.lvoxx.sssm.event.v1.EngagementType;
import com.lvoxx.sssm.post_service.domain.Engagement;
import com.lvoxx.sssm.post_service.domain.EngagementId;
import com.lvoxx.sssm.post_service.domain.EngagementKind;
import com.lvoxx.sssm.post_service.error.NotFoundException;
import com.lvoxx.sssm.post_service.outbox.OutboxEventFactory;
import com.lvoxx.sssm.post_service.repository.EngagementRepository;
import com.lvoxx.sssm.post_service.repository.OutboxRepository;
import com.lvoxx.sssm.post_service.repository.PostRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Engagement lifecycle: like/unlike, repost/unrepost, bookmark/unbookmark. Each add/remove is
 * idempotent — the {@code (post, actor, kind)} row's existence is the engagement, so re-liking is a
 * no-op and un-liking something not liked is a no-op (no double counts, no spurious events).
 *
 * <p>Every state change updates the post's denormalized count AND appends a {@code PostEngagement}
 * Protobuf event to the transactional outbox in the SAME transaction, so the count and the event
 * commit atomically (the relay publishes to Kafka afterwards). The actor is always the
 * gateway-forwarded identity, never a request body field.
 */
@Service
public class EngagementService {

    private final PostRepository posts;
    private final EngagementRepository engagements;
    private final OutboxRepository outbox;

    public EngagementService(
            PostRepository posts, EngagementRepository engagements, OutboxRepository outbox) {
        this.posts = posts;
        this.engagements = engagements;
        this.outbox = outbox;
    }

    @Transactional
    public void like(UUID actorId, UUID postId) {
        add(actorId, postId, EngagementKind.LIKE, EngagementType.ENGAGEMENT_TYPE_LIKE);
    }

    @Transactional
    public void unlike(UUID actorId, UUID postId) {
        remove(actorId, postId, EngagementKind.LIKE, EngagementType.ENGAGEMENT_TYPE_UNLIKE);
    }

    @Transactional
    public void repost(UUID actorId, UUID postId) {
        add(actorId, postId, EngagementKind.REPOST, EngagementType.ENGAGEMENT_TYPE_REPOST);
    }

    @Transactional
    public void unrepost(UUID actorId, UUID postId) {
        remove(actorId, postId, EngagementKind.REPOST, EngagementType.ENGAGEMENT_TYPE_UNREPOST);
    }

    @Transactional
    public void bookmark(UUID actorId, UUID postId) {
        add(actorId, postId, EngagementKind.BOOKMARK, EngagementType.ENGAGEMENT_TYPE_BOOKMARK);
    }

    @Transactional
    public void unbookmark(UUID actorId, UUID postId) {
        remove(actorId, postId, EngagementKind.BOOKMARK, EngagementType.ENGAGEMENT_TYPE_UNBOOKMARK);
    }

    /** Records an engagement (if not already present), bumps the count and emits the add event. */
    private void add(UUID actorId, UUID postId, EngagementKind kind, EngagementType eventType) {
        if (!posts.existsById(postId)) {
            throw new NotFoundException("No post with id " + postId);
        }
        EngagementId id = new EngagementId(postId, actorId, kind);
        if (engagements.existsById(id)) {
            return; // idempotent: already engaged
        }
        engagements.save(new Engagement(id));
        increment(postId, kind);
        outbox.save(OutboxEventFactory.postEngagement(postId, actorId, eventType, Instant.now()));
    }

    /** Removes an engagement (if present), drops the count and emits the remove event. */
    private void remove(UUID actorId, UUID postId, EngagementKind kind, EngagementType eventType) {
        EngagementId id = new EngagementId(postId, actorId, kind);
        if (!engagements.existsById(id)) {
            return; // idempotent: not engaged
        }
        engagements.deleteById(id);
        decrement(postId, kind);
        outbox.save(OutboxEventFactory.postEngagement(postId, actorId, eventType, Instant.now()));
    }

    private void increment(UUID postId, EngagementKind kind) {
        switch (kind) {
            case LIKE -> posts.incrementLikeCount(postId);
            case REPOST -> posts.incrementRepostCount(postId);
            case BOOKMARK -> posts.incrementBookmarkCount(postId);
        }
    }

    private void decrement(UUID postId, EngagementKind kind) {
        switch (kind) {
            case LIKE -> posts.decrementLikeCount(postId);
            case REPOST -> posts.decrementRepostCount(postId);
            case BOOKMARK -> posts.decrementBookmarkCount(postId);
        }
    }
}
