package io.github.lvoxx.post_service.repository;

import java.util.UUID;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import io.github.lvoxx.post_service.entity.Post;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface PostRepository extends ReactiveCrudRepository<Post, UUID> {

    Mono<Post> findByIdAndIsDeletedFalse(UUID id);

    @Query("SELECT * FROM posts WHERE reply_to_id = :postId AND is_deleted = false")
    Mono<Post> findByPostId(UUID postId);

    @Query("SELECT * FROM posts WHERE author_id = :authorId AND is_deleted = false ORDER BY created_at DESC LIMIT :limit")
    Flux<Post> findByAuthorIdOrderByCreatedAtDesc(UUID authorId, int limit);

    @Query("SELECT * FROM posts WHERE author_id IN (:authorIds) AND is_deleted = false AND status = 'PUBLISHED' ORDER BY created_at DESC LIMIT :limit")
    Flux<Post> findHomeFeed(java.util.List<UUID> authorIds, int limit);

}
