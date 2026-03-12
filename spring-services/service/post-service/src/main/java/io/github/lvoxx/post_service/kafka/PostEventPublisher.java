package io.github.lvoxx.post_service.kafka;

import java.time.Instant;
import java.util.UUID;

import org.apache.avro.specific.SpecificRecord;
import org.springframework.kafka.core.reactive.ReactiveKafkaProducerTemplate;
import org.springframework.stereotype.Component;

import io.github.lvoxx.common_core.util.UlidGenerator;
import io.github.lvoxx.post.PostCreatedEvent;
import io.github.lvoxx.post.PostLikedEvent;
import io.github.lvoxx.post.PostRepostedEvent;
import io.github.lvoxx.post_service.entity.Post;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * Publishes post domain events as Avro-serialised records to Kafka.
 *
 * <p>
 * Topics published:
 * <ul>
 * <li>{@code post.created} — new post successfully saved</li>
 * <li>{@code post.liked} — user liked a post</li>
 * <li>{@code post.reposted} — user reposted a post</li>
 * </ul>
 *
 * <p>
 * Avro schemas are defined in {@code common-core/src/main/avro/}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PostEventPublisher {

        private static final String SERVICE = "post-service";

        private final ReactiveKafkaProducerTemplate<String, SpecificRecord> kafka;

        /**
         * Publishes {@link PostCreatedEvent} to topic {@code post.created}.
         *
         * @param post the saved {@link Post} entity
         * @return {@link Mono} completing when Kafka acknowledges
         */
        public Mono<Void> publishPostCreated(Post post) {
                PostCreatedEvent event = PostCreatedEvent.newBuilder()
                                .setEventId(UlidGenerator.generate())
                                .setEventType("post.created")
                                .setVersion("1")
                                .setTimestamp(Instant.now())
                                .setProducerService(SERVICE)
                                .setPostId(post.getId().toString())
                                .setAuthorId(post.getAuthorId().toString())
                                .setContent(post.getContent())
                                .setPostType(post.getPostType())
                                .setGroupId(post.getGroupId() != null ? post.getGroupId().toString() : null)
                                .build();

                return send("post.created", post.getId().toString(), event);
        }

        /**
         * Publishes {@link PostLikedEvent} to topic {@code post.liked}.
         *
         * @param postId   UUID of the liked post
         * @param userId   UUID of the user who liked
         * @param authorId UUID of the post's author (for notification routing)
         * @return {@link Mono} completing when Kafka acknowledges
         */
        public Mono<Void> publishPostLiked(UUID postId, UUID userId, UUID authorId) {
                PostLikedEvent event = PostLikedEvent.newBuilder()
                                .setEventId(UlidGenerator.generate())
                                .setEventType("post.liked")
                                .setVersion("1")
                                .setTimestamp(Instant.now())
                                .setProducerService(SERVICE)
                                .setPostId(postId.toString())
                                .setUserId(userId.toString())
                                .setAuthorId(authorId != null ? authorId.toString() : "")
                                .build();

                return send("post.liked", postId.toString(), event);
        }

        /**
         * Publishes {@link PostRepostedEvent} to topic {@code post.reposted}.
         *
         * @param originalPostId UUID of the original post
         * @param newPostId      UUID of the newly created repost
         * @param userId         UUID of the user who reposted
         * @return {@link Mono} completing when Kafka acknowledges
         */
        public Mono<Void> publishPostReposted(UUID originalPostId, UUID newPostId, UUID userId) {
                PostRepostedEvent event = PostRepostedEvent.newBuilder()
                                .setEventId(UlidGenerator.generate())
                                .setEventType("post.reposted")
                                .setVersion("1")
                                .setTimestamp(Instant.now())
                                .setProducerService(SERVICE)
                                .setPostId(originalPostId.toString())
                                .setNewPostId(newPostId.toString())
                                .setUserId(userId.toString())
                                .build();

                return send("post.reposted", originalPostId.toString(), event);
        }

        private Mono<Void> send(String topic, String key, SpecificRecord record) {
                return kafka.send(topic, key, record)
                                .doOnSuccess(r -> log.info("Published Avro event topic={} key={} schema={}",
                                                topic, key, record.getSchema().getName()))
                                .doOnError(e -> log.error("Failed to publish Avro event topic={} key={}: {}",
                                                topic, key, e.getMessage()))
                                .then();
        }
}
