package io.github.lvoxx.user_service.kafka;

import java.time.Instant;
import java.util.UUID;

import org.apache.avro.specific.SpecificRecord;
import org.springframework.kafka.core.reactive.ReactiveKafkaProducerTemplate;
import org.springframework.stereotype.Component;

import io.github.lvoxx.common_core.util.UlidGenerator;
import io.github.lvoxx.common_keys.EventTypes;
import io.github.lvoxx.common_keys.ServiceNames;
import io.github.lvoxx.user.UserAvatarChangedEvent;
import io.github.lvoxx.user.UserFollowedEvent;
import io.github.lvoxx.user.UserProfileUpdatedEvent;
import io.github.lvoxx.user.UserUnfollowedEvent;
import io.github.lvoxx.user_service.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * Publishes user domain events to Kafka using Avro-serialised
 * {@link SpecificRecord} objects.
 *
 * <p>
 * Each method corresponds to one Kafka topic. The Avro schema is registered
 * automatically with the Confluent Schema Registry on first publish.
 *
 * <p>
 * Topics:
 * <ul>
 * <li>{@code user.profile.updated} — any profile field change</li>
 * <li>{@code user.avatar.changed} — avatar image replaced</li>
 * <li>{@code user.background.changed} — background image replaced</li>
 * <li>{@code user.followed} — A follows B</li>
 * <li>{@code user.unfollowed} — A unfollows B</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserEventPublisher {

        private final ReactiveKafkaProducerTemplate<String, SpecificRecord> kafka;

        /**
         * Publishes a {@link UserProfileUpdatedEvent} to {@code user.profile.updated}.
         *
         * @param user the updated {@link User} entity
         * @return a {@link Mono} that completes when the record has been acknowledged
         *         by Kafka
         */
        public Mono<Void> publishProfileUpdated(User user) {
                UserProfileUpdatedEvent event = UserProfileUpdatedEvent.newBuilder()
                                .setEventId(UlidGenerator.generate())
                                .setEventType(EventTypes.UserService.USER_PROFILE_UPDATED)
                                .setVersion("1")
                                .setTimestamp(Instant.now())
                                .setProducerService(ServiceNames.USER_SERVICE)
                                .setUserId(user.getId().toString())
                                .setUsername(user.getUsername())
                                .setDisplayName(user.getDisplayName())
                                .setAvatarUrl(user.getAvatarUrl())
                                .build();

                return send("user.profile.updated", user.getId().toString(), event);
        }

        /**
         * Publishes a {@link UserAvatarChangedEvent} to {@code user.avatar.changed}.
         *
         * @param user the user whose avatar was updated
         * @return a {@link Mono} that completes on acknowledgement
         */
        public Mono<Void> publishAvatarChanged(User user) {
                UserAvatarChangedEvent event = UserAvatarChangedEvent.newBuilder()
                                .setEventId(UlidGenerator.generate())
                                .setEventType(EventTypes.UserService.USER_AVATAR_CHANGED)
                                .setVersion("1")
                                .setTimestamp(Instant.now())
                                .setProducerService(ServiceNames.USER_SERVICE)
                                .setUserId(user.getId().toString())
                                .setAvatarUrl(user.getAvatarUrl() != null ? user.getAvatarUrl() : "")
                                .build();

                return send("user.avatar.changed", user.getId().toString(), event);
        }

        /**
         * Publishes a {@link UserAvatarChangedEvent} to {@code user.background.changed}
         * reusing the avatar schema (same shape, different topic).
         *
         * @param user the user whose background was updated
         * @return a {@link Mono} that completes on acknowledgement
         */
        public Mono<Void> publishBackgroundChanged(User user) {
                UserAvatarChangedEvent event = UserAvatarChangedEvent.newBuilder()
                                .setEventId(UlidGenerator.generate())
                                .setEventType(EventTypes.UserService.USER_BACKGROUND_CHANGED)
                                .setVersion("1")
                                .setTimestamp(Instant.now())
                                .setProducerService(ServiceNames.USER_SERVICE)
                                .setUserId(user.getId().toString())
                                .setAvatarUrl(user.getBackgroundUrl() != null ? user.getBackgroundUrl() : "")
                                .build();

                return send("user.background.changed", user.getId().toString(), event);
        }

        /**
         * Publishes a {@link UserFollowedEvent} to {@code user.followed}.
         *
         * @param followerId       UUID of the user who initiated the follow
         * @param followingId      UUID of the user who was followed
         * @param followerUsername username of the follower (for notification text)
         * @return a {@link Mono} that completes on acknowledgement
         */
        public Mono<Void> publishFollowed(UUID followerId, UUID followingId, String followerUsername) {
                UserFollowedEvent event = UserFollowedEvent.newBuilder()
                                .setEventId(UlidGenerator.generate())
                                .setEventType(EventTypes.UserService.USER_FOLLOWED)
                                .setVersion("1")
                                .setTimestamp(Instant.now())
                                .setProducerService(ServiceNames.USER_SERVICE)
                                .setFollowerId(followerId.toString())
                                .setFollowingId(followingId.toString())
                                .setFollowerUsername(followerUsername != null ? followerUsername : "")
                                .build();

                return send("user.followed", followerId.toString(), event);
        }

        /**
         * Publishes a {@link UserUnfollowedEvent} to {@code user.unfollowed}.
         *
         * @param followerId  UUID of the user who unfollowed
         * @param followingId UUID of the user who was unfollowed
         * @return a {@link Mono} that completes on acknowledgement
         */
        public Mono<Void> publishUnfollowed(UUID followerId, UUID followingId) {
                UserUnfollowedEvent event = UserUnfollowedEvent.newBuilder()
                                .setEventId(UlidGenerator.generate())
                                .setEventType(EventTypes.UserService.USER_UNFOLLOWED)
                                .setVersion("1")
                                .setTimestamp(Instant.now())
                                .setProducerService(ServiceNames.USER_SERVICE)
                                .setFollowerId(followerId.toString())
                                .setFollowingId(followingId.toString())
                                .build();

                return send("user.unfollowed", followerId.toString(), event);
        }

        private Mono<Void> send(String topic, String key, SpecificRecord record) {
                return kafka.send(topic, key, record)
                                .doOnSuccess(r -> log.info("Published Avro event to topic={} key={} schema={}",
                                                topic, key, record.getSchema().getName()))
                                .doOnError(e -> log.error("Failed to publish Avro event to topic={} key={}: {}",
                                                topic, key, e.getMessage()))
                                .then();
        }
}
