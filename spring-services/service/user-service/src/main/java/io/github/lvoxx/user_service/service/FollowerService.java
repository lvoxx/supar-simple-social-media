package io.github.lvoxx.user_service.service;

import java.util.UUID;

import io.github.lvoxx.common_core.model.PageResponse;
import io.github.lvoxx.user_service.dto.UserResponse;
import reactor.core.publisher.Mono;

public interface FollowerService {

    /**
     * Creates the follow relationship, increments follower/following counters,
     * and publishes a {@code user.followed} Kafka event.
     * Acquires a distributed lock per (follower, target) pair.
     * 409 if the relationship already exists.
     */
    Mono<Void> follow(UUID followerId, UUID targetId, String followerUsername);

    /**
     * Removes the follow relationship, decrements counters, and publishes
     * a {@code user.unfollowed} Kafka event.
     * 409 if the relationship does not exist.
     */
    Mono<Void> unfollow(UUID followerId, UUID targetId);

    /**
     * Returns {@code true} if {@code followerId} currently follows {@code targetId}.
     */
    Mono<Boolean> isFollowing(UUID followerId, UUID targetId);

    /**
     * Returns a cursor-paginated page of users who follow {@code userId}.
     * Result is cached per (userId, cursor, size).
     */
    Mono<PageResponse<UserResponse>> getFollowers(UUID userId, String cursor, int size);

    /**
     * Returns a cursor-paginated page of users that {@code userId} follows.
     * Result is cached per (userId, cursor, size).
     */
    Mono<PageResponse<UserResponse>> getFollowing(UUID userId, String cursor, int size);
}
