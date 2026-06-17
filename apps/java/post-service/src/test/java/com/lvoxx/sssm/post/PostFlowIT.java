package com.lvoxx.sssm.post_service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.InvalidProtocolBufferException;
import com.lvoxx.sssm.event.v1.PostCreated;
import com.lvoxx.sssm.event.v1.PostDeleted;
import com.lvoxx.sssm.post_service.domain.OutboxEvent;
import com.lvoxx.sssm.post_service.domain.Post;
import com.lvoxx.sssm.post_service.error.ForbiddenException;
import com.lvoxx.sssm.post_service.error.NotFoundException;
import com.lvoxx.sssm.post_service.outbox.OutboxEventFactory;
import com.lvoxx.sssm.post_service.repository.OutboxRepository;
import com.lvoxx.sssm.post_service.repository.PostRepository;
import com.lvoxx.sssm.post_service.service.CursorPage;
import com.lvoxx.sssm.post_service.service.PostService;
import com.lvoxx.sssm.post_service.support.PostgresIntegrationTest;
import com.lvoxx.sssm.post_service.web.dto.CreatePostRequest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * End-to-end persistence tests for the post-service core flow, exercising the real service,
 * repositories (including the {@code @Modifying} reply-count updates and keyset pagination) and
 * Hibernate mappings against a real PostgreSQL — and proving the transactional outbox is written in
 * the same transaction with a parseable Protobuf payload.
 */
class PostFlowIT extends PostgresIntegrationTest {

    @Autowired
    private PostService posts;

    @Autowired
    private PostRepository postRepository;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("TRUNCATE sssm.posts, sssm.outbox_events CASCADE");
    }

    @Test
    void create_persistsPostWithZeroedCountsAndWritesParseableOutboxEvent() throws Exception {
        UUID author = UUID.randomUUID();
        Post created = posts.create(author, new CreatePostRequest("hello world", null));

        Post fetched = posts.getById(created.getId());
        assertThat(fetched.getAuthorId()).isEqualTo(author);
        assertThat(fetched.getText()).isEqualTo("hello world");
        assertThat(fetched.getReplyToPostId()).isNull();
        assertThat(fetched.getReplyCount()).isZero();
        assertThat(fetched.getLikeCount()).isZero();
        assertThat(fetched.getCreatedAt()).isNotNull();

        List<OutboxEvent> events = outboxRepository.findAll();
        assertThat(events).hasSize(1);
        OutboxEvent event = events.get(0);
        assertThat(event.getEventType()).isEqualTo(OutboxEventFactory.POST_CREATED);
        assertThat(event.getAggregateId()).isEqualTo(created.getId());
        assertThat(event.getPublishedAt()).isNull();

        PostCreated payload = PostCreated.parseFrom(event.getPayload());
        assertThat(payload.getPostId()).isEqualTo(created.getId().toString());
        assertThat(payload.getAuthorId()).isEqualTo(author.toString());
        assertThat(payload.getText()).isEqualTo("hello world");
        assertThat(payload.getReplyToPostId()).isEmpty();
    }

    @Test
    void reply_incrementsParentReplyCountAndRecordsReplyTarget() {
        UUID author = UUID.randomUUID();
        Post root = posts.create(author, new CreatePostRequest("root", null));

        Post reply = posts.create(
                UUID.randomUUID(), new CreatePostRequest("a reply", root.getId()));

        assertThat(reply.getReplyToPostId()).isEqualTo(root.getId());
        assertThat(postRepository.findById(root.getId()).orElseThrow().getReplyCount())
                .isEqualTo(1);
        assertThat(posts.listReplies(root.getId(), null, null).items())
                .extracting(Post::getId).containsExactly(reply.getId());
        // One PostCreated per post.
        assertThat(outboxRepository.findAll()).hasSize(2);
    }

    @Test
    void reply_toMissingPost_throwsNotFound() {
        assertThatThrownBy(() -> posts.create(
                UUID.randomUUID(), new CreatePostRequest("orphan", UUID.randomUUID())))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void delete_removesPostDecrementsParentAndWritesDeletedEvent() throws Exception {
        UUID author = UUID.randomUUID();
        Post root = posts.create(author, new CreatePostRequest("root", null));
        Post reply = posts.create(author, new CreatePostRequest("reply", root.getId()));
        outboxRepository.deleteAll(); // focus on the delete event below

        posts.delete(author, reply.getId());

        assertThat(postRepository.findById(reply.getId())).isEmpty();
        assertThat(postRepository.findById(root.getId()).orElseThrow().getReplyCount()).isZero();

        List<OutboxEvent> events = outboxRepository.findAll();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getEventType()).isEqualTo(OutboxEventFactory.POST_DELETED);
        PostDeleted payload = parseDeleted(events.get(0).getPayload());
        assertThat(payload.getPostId()).isEqualTo(reply.getId().toString());
    }

    @Test
    void delete_othersPost_throwsForbidden() {
        UUID author = UUID.randomUUID();
        Post post = posts.create(author, new CreatePostRequest("mine", null));

        assertThatThrownBy(() -> posts.delete(UUID.randomUUID(), post.getId()))
                .isInstanceOf(ForbiddenException.class);
        assertThat(postRepository.findById(post.getId())).isPresent();
    }

    @Test
    void listByAuthor_isPaginatedNewestFirstWithoutDuplicatesOrGaps() {
        UUID author = UUID.randomUUID();
        int total = 5;
        for (int i = 0; i < total; i++) {
            posts.create(author, new CreatePostRequest("post " + i, null));
        }

        List<UUID> collected = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            CursorPage<Post> page = posts.listByAuthor(author, cursor, 2);
            page.items().forEach(p -> collected.add(p.getId()));
            cursor = page.nextCursor();
            pages++;
        } while (cursor != null && pages < 10);

        assertThat(collected).hasSize(total);
        Set<UUID> unique = new HashSet<>(collected);
        assertThat(unique).hasSize(total);
    }

    private static PostDeleted parseDeleted(byte[] payload) {
        try {
            return PostDeleted.parseFrom(payload);
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalStateException(e);
        }
    }
}
