package io.github.lvoxx.post_service.service;

import java.util.UUID;

import io.github.lvoxx.common_core.model.PageResponse;
import io.github.lvoxx.common_core.security.UserPrincipal;
import io.github.lvoxx.post_service.dto.CreatePostRequest;
import io.github.lvoxx.post_service.dto.PostResponse;
import reactor.core.publisher.Mono;

public interface PostService {
    Mono<PostResponse> createPost(UserPrincipal principal, CreatePostRequest req);

    Mono<PostResponse> getPost(UUID postId);

    Mono<Void> deletePost(UserPrincipal principal, UUID postId);

    Mono<PageResponse<PostResponse>> getHomeFeed(UserPrincipal principal, String cursor, int size);

    Mono<PageResponse<PostResponse>> getUserPosts(UUID userId, String cursor, int size);
}
