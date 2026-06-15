package com.lvoxx.sssm.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lvoxx.sssm.event.v1.EngagementType;
import com.lvoxx.sssm.event.v1.PostEngagement;
import com.lvoxx.sssm.post.domain.OutboxEvent;
import com.lvoxx.sssm.post.domain.Post;
import com.lvoxx.sssm.post.error.NotFoundException;
import com.lvoxx.sssm.post.outbox.OutboxEventFactory;
import com.lvoxx.sssm.post.repository.OutboxRepository;
import com.lvoxx.sssm.post.repository.PostRepository;
import com.lvoxx.sssm.post.service.EngagementService;
import com.lvoxx.sssm.post.service.PostService;
import com.lvoxx.sssm.post.support.PostgresIntegrationTest;
import com.lvoxx.sssm.post.web.dto.CreatePostRequest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * End-to-end persistence tests for engagement (like / repost / bookmark) against a real PostgreSQL:
 * proves the {@code post_engagements} mapping + composite key, the denormalized count maintenance,
 * idempotency, and that a parseable {@code PostEngagement} Protobuf lands in the transactional outbox
 * in the same transaction.
 */
class EngagementFlowIT extends PostgresIntegrationTest {

    @Autowired
    private PostService posts;

    @Autowired
    private EngagementService engagements;

    @Autowired
    private PostRepository postRepository;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("TRUNCATE sssm.posts, sssm.outbox_events, sssm.post_engagements CASCADE");
    }

    @Test
    void like_incrementsCountPersistsRowAndWritesParseableEvent() throws Exception {
        Post post = posts.create(UUID.randomUUID(), new CreatePostRequest("hi", null));
        outboxRepository.deleteAll(); // focus on the engagement event
        UUID actor = UUID.randomUUID();

        engagements.like(actor, post.getId());

        assertThat(postRepository.findById(post.getId()).orElseThrow().getLikeCount()).isEqualTo(1);
        assertThat(rowCount("post_engagements")).isEqualTo(1);

        List<OutboxEvent> events = outboxRepository.findAll();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getEventType()).isEqualTo(OutboxEventFactory.POST_ENGAGEMENT);
        assertThat(events.get(0).getAggregateId()).isEqualTo(post.getId());

        PostEngagement payload = PostEngagement.parseFrom(events.get(0).getPayload());
        assertThat(payload.getPostId()).isEqualTo(post.getId().toString());
        assertThat(payload.getActorId()).isEqualTo(actor.toString());
        assertThat(payload.getType()).isEqualTo(EngagementType.ENGAGEMENT_TYPE_LIKE);
    }

    @Test
    void like_isIdempotent_doesNotDoubleCountOrDoubleEmit() {
        Post post = posts.create(UUID.randomUUID(), new CreatePostRequest("hi", null));
        outboxRepository.deleteAll();
        UUID actor = UUID.randomUUID();

        engagements.like(actor, post.getId());
        engagements.like(actor, post.getId());

        assertThat(postRepository.findById(post.getId()).orElseThrow().getLikeCount()).isEqualTo(1);
        assertThat(rowCount("post_engagements")).isEqualTo(1);
        assertThat(outboxRepository.findAll()).hasSize(1);
    }

    @Test
    void unlike_decrementsCountRemovesRowAndEmitsUnlikeEvent() throws Exception {
        Post post = posts.create(UUID.randomUUID(), new CreatePostRequest("hi", null));
        UUID actor = UUID.randomUUID();
        engagements.like(actor, post.getId());
        outboxRepository.deleteAll();

        engagements.unlike(actor, post.getId());

        assertThat(postRepository.findById(post.getId()).orElseThrow().getLikeCount()).isZero();
        assertThat(rowCount("post_engagements")).isZero();

        List<OutboxEvent> events = outboxRepository.findAll();
        assertThat(events).hasSize(1);
        PostEngagement payload = PostEngagement.parseFrom(events.get(0).getPayload());
        assertThat(payload.getType()).isEqualTo(EngagementType.ENGAGEMENT_TYPE_UNLIKE);
    }

    @Test
    void unlike_whenNotLiked_isNoOp() {
        Post post = posts.create(UUID.randomUUID(), new CreatePostRequest("hi", null));
        outboxRepository.deleteAll();

        engagements.unlike(UUID.randomUUID(), post.getId());

        assertThat(postRepository.findById(post.getId()).orElseThrow().getLikeCount()).isZero();
        assertThat(outboxRepository.findAll()).isEmpty();
    }

    @Test
    void repostAndBookmark_areIndependentCountsForTheSameActorAndPost() {
        Post post = posts.create(UUID.randomUUID(), new CreatePostRequest("hi", null));
        UUID actor = UUID.randomUUID();

        engagements.like(actor, post.getId());
        engagements.repost(actor, post.getId());
        engagements.bookmark(actor, post.getId());

        Post fetched = postRepository.findById(post.getId()).orElseThrow();
        assertThat(fetched.getLikeCount()).isEqualTo(1);
        assertThat(fetched.getRepostCount()).isEqualTo(1);
        assertThat(fetched.getBookmarkCount()).isEqualTo(1);
        // Three distinct (post, actor, kind) rows.
        assertThat(rowCount("post_engagements")).isEqualTo(3);
    }

    @Test
    void like_onMissingPost_throwsNotFound() {
        assertThatThrownBy(() -> engagements.like(UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class);
    }

    private long rowCount(String table) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM sssm." + table, Long.class);
        return n == null ? 0 : n;
    }
}
