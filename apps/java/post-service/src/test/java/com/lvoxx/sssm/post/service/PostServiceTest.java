package com.lvoxx.sssm.post.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lvoxx.sssm.post.domain.OutboxEvent;
import com.lvoxx.sssm.post.domain.Post;
import com.lvoxx.sssm.post.error.ForbiddenException;
import com.lvoxx.sssm.post.error.NotFoundException;
import com.lvoxx.sssm.post.repository.OutboxRepository;
import com.lvoxx.sssm.post.repository.PostRepository;
import com.lvoxx.sssm.post.web.dto.CreatePostRequest;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests for the post lifecycle logic in isolation (mocked repositories): reply validation,
 * denormalized reply-count maintenance, outbox writes, and delete authorization.
 */
class PostServiceTest {

    private PostRepository posts;
    private OutboxRepository outbox;
    private PostService service;

    @BeforeEach
    void setUp() {
        posts = org.mockito.Mockito.mock(PostRepository.class);
        outbox = org.mockito.Mockito.mock(OutboxRepository.class);
        service = new PostService(posts, outbox);
    }

    @Test
    void create_root_savesPostAndWritesCreatedEventWithoutTouchingReplyCount() {
        UUID author = UUID.randomUUID();
        when(posts.saveAndFlush(any(Post.class)))
                .thenReturn(persisted(author, "hi", null));

        service.create(author, new CreatePostRequest("hi", null));

        verify(outbox).save(any(OutboxEvent.class));
        verify(posts, never()).incrementReplyCount(any());
    }

    @Test
    void create_reply_incrementsParentReplyCountAndWritesEvent() {
        UUID author = UUID.randomUUID();
        UUID parent = UUID.randomUUID();
        when(posts.existsById(parent)).thenReturn(true);
        when(posts.saveAndFlush(any(Post.class)))
                .thenReturn(persisted(author, "re", parent));

        service.create(author, new CreatePostRequest("re", parent));

        verify(posts).incrementReplyCount(parent);
        verify(outbox).save(any(OutboxEvent.class));
    }

    @Test
    void create_replyToMissingPost_throwsNotFoundAndPersistsNothing() {
        UUID parent = UUID.randomUUID();
        when(posts.existsById(parent)).thenReturn(false);

        assertThatThrownBy(() -> service.create(
                UUID.randomUUID(), new CreatePostRequest("re", parent)))
                .isInstanceOf(NotFoundException.class);

        verify(posts, never()).saveAndFlush(any());
        verify(outbox, never()).save(any());
    }

    @Test
    void delete_othersPost_throwsForbiddenAndDeletesNothing() {
        UUID owner = UUID.randomUUID();
        Post post = persisted(owner, "mine", null);
        when(posts.findById(post.getId())).thenReturn(Optional.of(post));

        assertThatThrownBy(() -> service.delete(UUID.randomUUID(), post.getId()))
                .isInstanceOf(ForbiddenException.class);

        verify(posts, never()).delete(any());
        verify(outbox, never()).save(any());
    }

    @Test
    void delete_ownReply_decrementsParentAndWritesDeletedEvent() {
        UUID owner = UUID.randomUUID();
        UUID parent = UUID.randomUUID();
        Post reply = persisted(owner, "re", parent);
        when(posts.findById(reply.getId())).thenReturn(Optional.of(reply));

        service.delete(owner, reply.getId());

        verify(posts).decrementReplyCount(parent);
        verify(posts).delete(reply);
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).save(captor.capture());
    }

    /** A Post as it looks after persistence: id and created_at populated (normally by Hibernate). */
    private static Post persisted(UUID author, String text, UUID replyTo) {
        Post post = new Post(author, text, replyTo);
        ReflectionTestUtils.setField(post, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(post, "createdAt", Instant.now());
        return post;
    }
}
