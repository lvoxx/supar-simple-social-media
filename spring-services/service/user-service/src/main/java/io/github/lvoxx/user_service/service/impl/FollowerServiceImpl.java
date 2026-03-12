package io.github.lvoxx.user_service.service.impl;

import java.time.Instant;
import java.util.UUID;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.lvoxx.common_core.exception.ConflictException;
import io.github.lvoxx.common_core.message.MessageKeys;
import io.github.lvoxx.common_core.model.PageResponse;
import io.github.lvoxx.common_keys.CacheKeys;
import io.github.lvoxx.common_keys.LockKeys;
import io.github.lvoxx.redis_starter.service.LockService;
import io.github.lvoxx.user_service.dto.UserResponse;
import io.github.lvoxx.user_service.entity.Follower;
import io.github.lvoxx.user_service.entity.User;
import io.github.lvoxx.user_service.kafka.UserEventPublisher;
import io.github.lvoxx.user_service.repository.FollowerRepository;
import io.github.lvoxx.user_service.repository.UserRepository;
import io.github.lvoxx.user_service.service.FollowerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@Slf4j
@Service
@RequiredArgsConstructor
public class FollowerServiceImpl implements FollowerService {

    private final FollowerRepository followerRepo;
    private final UserRepository userRepo;
    private final UserEventPublisher eventPublisher;
    private final LockService lockService;

    // ── Write ─────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    @Caching(evict = {
            @CacheEvict(value = CacheKeys.UserService.FOLLOWERS_LIST,   key = "#targetId"),
            @CacheEvict(value = CacheKeys.UserService.FOLLOWING_LIST,   key = "#followerId"),
            @CacheEvict(value = CacheKeys.UserService.FOLLOWER_COUNT,   key = "#targetId"),
            @CacheEvict(value = CacheKeys.UserService.FOLLOWING_COUNT,  key = "#followerId")
    })
    public Mono<Void> follow(UUID followerId, UUID targetId, String followerUsername) {
        return lockService.withLock(LockKeys.follow(followerId, targetId), () ->
                followerRepo.existsByFollowerIdAndFollowingId(followerId, targetId)
                        .flatMap(exists -> {
                            if (exists) {
                                return Mono.error(new ConflictException(
                                        MessageKeys.ALREADY_FOLLOWING, targetId));
                            }
                            return followerRepo.save(Follower.builder()
                                            .followerId(followerId)
                                            .followingId(targetId)
                                            .createdAt(Instant.now())
                                            .build())
                                    .then(userRepo.incrementFollowerCount(targetId, 1))
                                    .then(userRepo.incrementFollowingCount(followerId, 1))
                                    .then(eventPublisher.publishFollowed(
                                            followerId, targetId, followerUsername));
                        }));
    }

    @Override
    @Transactional
    @Caching(evict = {
            @CacheEvict(value = CacheKeys.UserService.FOLLOWERS_LIST,   key = "#targetId"),
            @CacheEvict(value = CacheKeys.UserService.FOLLOWING_LIST,   key = "#followerId"),
            @CacheEvict(value = CacheKeys.UserService.FOLLOWER_COUNT,   key = "#targetId"),
            @CacheEvict(value = CacheKeys.UserService.FOLLOWING_COUNT,  key = "#followerId")
    })
    public Mono<Void> unfollow(UUID followerId, UUID targetId) {
        return followerRepo.existsByFollowerIdAndFollowingId(followerId, targetId)
                .flatMap(exists -> {
                    if (!exists) {
                        return Mono.error(new ConflictException(
                                MessageKeys.NOT_FOLLOWING, targetId));
                    }
                    return followerRepo.deleteByFollowerIdAndFollowingId(followerId, targetId)
                            .then(userRepo.incrementFollowerCount(targetId, -1))
                            .then(userRepo.incrementFollowingCount(followerId, -1))
                            .then(eventPublisher.publishUnfollowed(followerId, targetId));
                });
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    @Override
    public Mono<Boolean> isFollowing(UUID followerId, UUID targetId) {
        // Not cached: called in the follow hot-path where stale data would cause bugs.
        return followerRepo.existsByFollowerIdAndFollowingId(followerId, targetId);
    }

    @Override
    @Cacheable(value = CacheKeys.UserService.FOLLOWERS_LIST,
            key = "#userId + ':' + #cursor + ':' + #size")
    public Mono<PageResponse<UserResponse>> getFollowers(UUID userId, String cursor, int size) {
        return followerRepo.findFollowersByUserId(userId, size)
                .flatMap(f -> userRepo.findByIdAndIsDeletedFalse(f.getFollowerId()))
                .map(this::toResponse)
                .collectList()
                .map(items -> PageResponse.of(items, items.size() >= size ? "next" : null));
    }

    @Override
    @Cacheable(value = CacheKeys.UserService.FOLLOWING_LIST,
            key = "#userId + ':' + #cursor + ':' + #size")
    public Mono<PageResponse<UserResponse>> getFollowing(UUID userId, String cursor, int size) {
        return followerRepo.findFollowingByUserId(userId, size)
                .flatMap(f -> userRepo.findByIdAndIsDeletedFalse(f.getFollowingId()))
                .map(this::toResponse)
                .collectList()
                .map(items -> PageResponse.of(items, items.size() >= size ? "next" : null));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private UserResponse toResponse(User u) {
        return new UserResponse(
                u.getId(), u.getUsername(), u.getDisplayName(), u.getBio(),
                u.getAvatarUrl(), u.getBackgroundUrl(), u.getWebsiteUrl(), u.getLocation(),
                u.getIsVerified(), u.getIsPrivate(),
                u.getFollowerCount(), u.getFollowingCount(), u.getPostCount(),
                u.getRole(), u.getCreatedAt());
    }
}
