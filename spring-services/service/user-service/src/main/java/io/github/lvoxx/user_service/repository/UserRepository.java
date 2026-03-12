package io.github.lvoxx.user_service.repository;

import java.util.UUID;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import io.github.lvoxx.user_service.entity.User;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface UserRepository extends ReactiveCrudRepository<User, UUID> {

    Mono<User> findByUsernameAndIsDeletedFalse(String username);

    Mono<User> findByIdAndIsDeletedFalse(UUID id);

    Mono<User> findByKeycloakId(UUID keycloakId);

    Mono<Boolean> existsByUsername(String username);

    @Query("SELECT * FROM users WHERE (username ILIKE '%' || :query || '%' OR display_name ILIKE '%' || :query || '%') AND is_deleted = false LIMIT :limit")
    Flux<User> searchByUsernameOrDisplayName(String query, int limit);

    @Query("UPDATE users SET follower_count = follower_count + :delta WHERE id = :userId")
    Mono<Void> incrementFollowerCount(UUID userId, int delta);

    @Query("UPDATE users SET following_count = following_count + :delta WHERE id = :userId")
    Mono<Void> incrementFollowingCount(UUID userId, int delta);

    @Query("UPDATE users SET post_count = post_count + :delta WHERE id = :userId")
    Mono<Void> incrementPostCount(UUID userId, int delta);
}
