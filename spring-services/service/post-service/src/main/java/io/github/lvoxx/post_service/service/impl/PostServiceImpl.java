package io.github.lvoxx.post_service.service.impl;

import java.util.UUID;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import io.github.lvoxx.common_core.exception.ForbiddenException;
import io.github.lvoxx.common_core.exception.ResourceNotFoundException;
import io.github.lvoxx.common_core.message.MessageKeys;
import io.github.lvoxx.common_core.model.PageResponse;
import io.github.lvoxx.common_core.security.UserPrincipal;
import io.github.lvoxx.common_core.util.UlidGenerator;
import io.github.lvoxx.common_keys.CacheKeys;
import io.github.lvoxx.post_service.dto.CreatePostRequest;
import io.github.lvoxx.post_service.dto.PostResponse;
import io.github.lvoxx.post_service.entity.Post;
import io.github.lvoxx.post_service.kafka.PostEventPublisher;
import io.github.lvoxx.post_service.repository.PostRepository;
import io.github.lvoxx.post_service.service.PostService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@Slf4j
@Service
@RequiredArgsConstructor
public class PostServiceImpl implements PostService {

    private final PostRepository postRepo;
    private final PostEventPublisher eventPublisher;

    @Override
    public Mono<PostResponse> createPost(UserPrincipal principal, CreatePostRequest req) {
        Post post = Post.builder()
                .id(UlidGenerator.generateAsUUID())
                .authorId(principal.userId())
                .content(req.content())
                .groupId(req.groupId())
                .replyToId(req.replyToId())
                .repostOfId(req.repostOfId())
                .postType(req.postType() != null ? req.postType() : "ORIGINAL")
                .status("PUBLISHED")
                .visibility(req.visibility() != null ? req.visibility() : "PUBLIC")
                .build();

        return postRepo.save(post)
                .flatMap(saved -> eventPublisher.publishPostCreated(saved).thenReturn(saved))
                .map(this::toResponse);
    }

    @Override
    @Cacheable(value = CacheKeys.Post.POST_DETAIL, key = "#postId")
    public Mono<PostResponse> getPost(UUID postId) {
        return postRepo.findByIdAndIsDeletedFalse(postId)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException(MessageKeys.POST_NOT_FOUND, postId)))
                .map(this::toResponse);
    }

    @Override
    @CacheEvict(value = CacheKeys.Post.POST_DETAIL, key = "#postId")
    public Mono<Void> deletePost(UserPrincipal principal, UUID postId) {
        return postRepo.findByIdAndIsDeletedFalse(postId)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException(MessageKeys.POST_NOT_FOUND, postId)))
                .flatMap(post -> {
                    if (!post.getAuthorId().equals(principal.userId()) && !principal.isAdmin()) {
                        return Mono.error(new ForbiddenException(MessageKeys.FORBIDDEN));
                    }
                    post.softDelete(principal.userId());
                    return postRepo.save(post);
                })
                .then();
    }

    @Override
    public Mono<PageResponse<PostResponse>> getHomeFeed(UserPrincipal principal, String cursor, int size) {
        // Simplified — real impl would load followed users from user-service via
        // WebClient/cache
        return postRepo.findByAuthorIdOrderByCreatedAtDesc(principal.userId(), size)
                .map(this::toResponse)
                .collectList()
                .map(items -> PageResponse.of(items, items.size() >= size ? "next" : null));
    }

    @Override
    public Mono<PageResponse<PostResponse>> getUserPosts(UUID userId, String cursor, int size) {
        return postRepo.findByAuthorIdOrderByCreatedAtDesc(userId, size)
                .map(this::toResponse)
                .collectList()
                .map(items -> PageResponse.of(items, items.size() >= size ? "next" : null));
    }

    private PostResponse toResponse(Post p) {
        return new PostResponse(p.getId(), p.getAuthorId(), p.getGroupId(), p.getContent(),
                p.getPostType(), p.getStatus(), p.getVisibility(),
                p.getIsEdited(), p.getIsPinned(), p.getReplyToId(), p.getRepostOfId(), p.getCreatedAt());
    }
}
