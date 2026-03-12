package io.github.lvoxx.user_service.repository;

import java.util.UUID;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import io.github.lvoxx.user_service.entity.Follower;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface FollowerRepository extends ReactiveCrudRepository<Follower, UUID> {

    Mono<Boolean> existsByFollowerIdAndFollowingId(UUID followerId, UUID followingId);

    Mono<Void> deleteByFollowerIdAndFollowingId(UUID followerId, UUID followingId);

    @Query("SELECT * FROM followers WHERE following_id = :userId ORDER BY created_at DESC LIMIT :limit")
    Flux<Follower> findFollowersByUserId(UUID userId, int limit);

    @Query("SELECT * FROM followers WHERE follower_id = :userId ORDER BY created_at DESC LIMIT :limit")
    Flux<Follower> findFollowingByUserId(UUID userId, int limit);
}
