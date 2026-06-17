package com.lvoxx.sssm.post_service.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lvoxx.sssm.post_service.domain.Engagement;
import com.lvoxx.sssm.post_service.domain.EngagementId;
import com.lvoxx.sssm.post_service.domain.OutboxEvent;
import com.lvoxx.sssm.post_service.error.NotFoundException;
import com.lvoxx.sssm.post_service.repository.EngagementRepository;
import com.lvoxx.sssm.post_service.repository.OutboxRepository;
import com.lvoxx.sssm.post_service.repository.PostRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for engagement logic in isolation (mocked repositories): idempotency (re-engaging and
 * un-engaging-when-absent are no-ops), denormalized count maintenance per kind, outbox writes, and
 * the not-found guard when engaging a missing post.
 */
class EngagementServiceTest {

    private PostRepository posts;
    private EngagementRepository engagements;
    private OutboxRepository outbox;
    private EngagementService service;

    @BeforeEach
    void setUp() {
        posts = mock(PostRepository.class);
        engagements = mock(EngagementRepository.class);
        outbox = mock(OutboxRepository.class);
        service = new EngagementService(posts, engagements, outbox);
    }

    @Test
    void like_whenNotYetLiked_savesIncrementsAndEmitsEvent() {
        UUID actor = UUID.randomUUID();
        UUID post = UUID.randomUUID();
        when(posts.existsById(post)).thenReturn(true);
        when(engagements.existsById(any(EngagementId.class))).thenReturn(false);

        service.like(actor, post);

        verify(engagements).save(any(Engagement.class));
        verify(posts).incrementLikeCount(post);
        verify(outbox).save(any(OutboxEvent.class));
    }

    @Test
    void like_whenAlreadyLiked_isNoOp() {
        UUID actor = UUID.randomUUID();
        UUID post = UUID.randomUUID();
        when(posts.existsById(post)).thenReturn(true);
        when(engagements.existsById(any(EngagementId.class))).thenReturn(true);

        service.like(actor, post);

        verify(engagements, never()).save(any());
        verify(posts, never()).incrementLikeCount(any());
        verify(outbox, never()).save(any());
    }

    @Test
    void like_onMissingPost_throwsNotFoundAndPersistsNothing() {
        UUID post = UUID.randomUUID();
        when(posts.existsById(post)).thenReturn(false);

        assertThatThrownBy(() -> service.like(UUID.randomUUID(), post))
                .isInstanceOf(NotFoundException.class);

        verify(engagements, never()).save(any());
        verify(outbox, never()).save(any());
    }

    @Test
    void unlike_whenLiked_deletesDecrementsAndEmitsEvent() {
        UUID actor = UUID.randomUUID();
        UUID post = UUID.randomUUID();
        when(engagements.existsById(any(EngagementId.class))).thenReturn(true);

        service.unlike(actor, post);

        verify(engagements).deleteById(any(EngagementId.class));
        verify(posts).decrementLikeCount(post);
        verify(outbox).save(any(OutboxEvent.class));
    }

    @Test
    void unlike_whenNotLiked_isNoOp() {
        when(engagements.existsById(any(EngagementId.class))).thenReturn(false);

        service.unlike(UUID.randomUUID(), UUID.randomUUID());

        verify(engagements, never()).deleteById(any());
        verify(posts, never()).decrementLikeCount(any());
        verify(outbox, never()).save(any());
    }

    @Test
    void repost_bumpsRepostCount_notLikeCount() {
        UUID post = UUID.randomUUID();
        when(posts.existsById(post)).thenReturn(true);
        when(engagements.existsById(any(EngagementId.class))).thenReturn(false);

        service.repost(UUID.randomUUID(), post);

        verify(posts).incrementRepostCount(post);
        verify(posts, never()).incrementLikeCount(any());
    }

    @Test
    void bookmark_bumpsBookmarkCount_notLikeCount() {
        UUID post = UUID.randomUUID();
        when(posts.existsById(post)).thenReturn(true);
        when(engagements.existsById(any(EngagementId.class))).thenReturn(false);

        service.bookmark(UUID.randomUUID(), post);

        verify(posts).incrementBookmarkCount(post);
        verify(posts, never()).incrementLikeCount(any());
    }
}
