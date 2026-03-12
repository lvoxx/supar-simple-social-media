package io.github.lvoxx.user_service.service;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.github.lvoxx.common_core.exception.ConflictException;
import io.github.lvoxx.redis_starter.service.LockService;
import io.github.lvoxx.user_service.entity.Follower;
import io.github.lvoxx.user_service.entity.User;
import io.github.lvoxx.user_service.kafka.UserEventPublisher;
import io.github.lvoxx.user_service.repository.FollowerRepository;
import io.github.lvoxx.user_service.repository.UserRepository;
import io.github.lvoxx.user_service.service.impl.FollowerServiceImpl;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@Tag("unit")
@DisplayName("FollowerService — follow/unfollow and social graph reads")
@ExtendWith(MockitoExtension.class)
class FollowerServiceImplTest {

    @Mock private FollowerRepository followerRepo;
    @Mock private UserRepository userRepo;
    @Mock private UserEventPublisher eventPublisher;
    @Mock private LockService lockService;

    private FollowerServiceImpl followerService;

    private UUID followerId;
    private UUID targetId;

    @BeforeEach
    void setUp() {
        followerService = new FollowerServiceImpl(followerRepo, userRepo, eventPublisher, lockService);

        followerId = UUID.randomUUID();
        targetId   = UUID.randomUUID();

        // Lock executes the supplier immediately (no real Redis in unit tests)
        when(lockService.withLock(anyString(), any()))
                .thenAnswer(inv -> ((Supplier<Mono<?>>) inv.getArgument(1)).get());
    }

    // ── follow ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("follow: given already following → throws ConflictException")
    void follow_givenAlreadyFollowing_throwsConflictException() {
        when(followerRepo.existsByFollowerIdAndFollowingId(followerId, targetId))
                .thenReturn(Mono.just(true));

        StepVerifier.create(followerService.follow(followerId, targetId, "alice"))
                .expectError(ConflictException.class)
                .verify();
    }

    @Test
    @DisplayName("follow: given new follow → saves Follower, increments both counters, publishes event")
    void follow_givenNotFollowing_savesAndIncrementsCountsAndPublishesEvent() {
        Follower saved = Follower.builder()
                .followerId(followerId).followingId(targetId).createdAt(Instant.now()).build();

        when(followerRepo.existsByFollowerIdAndFollowingId(followerId, targetId)).thenReturn(Mono.just(false));
        when(followerRepo.save(any(Follower.class))).thenReturn(Mono.just(saved));
        when(userRepo.incrementFollowerCount(targetId, 1)).thenReturn(Mono.empty());
        when(userRepo.incrementFollowingCount(followerId, 1)).thenReturn(Mono.empty());
        when(eventPublisher.publishFollowed(followerId, targetId, "alice")).thenReturn(Mono.empty());

        StepVerifier.create(followerService.follow(followerId, targetId, "alice"))
                .verifyComplete();

        verify(followerRepo).save(any(Follower.class));
        verify(userRepo).incrementFollowerCount(targetId, 1);
        verify(userRepo).incrementFollowingCount(followerId, 1);
        verify(eventPublisher).publishFollowed(followerId, targetId, "alice");
    }

    // ── unfollow ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("unfollow: given not following → throws ConflictException")
    void unfollow_givenNotFollowing_throwsConflictException() {
        when(followerRepo.existsByFollowerIdAndFollowingId(followerId, targetId))
                .thenReturn(Mono.just(false));

        StepVerifier.create(followerService.unfollow(followerId, targetId))
                .expectError(ConflictException.class)
                .verify();
    }

    @Test
    @DisplayName("unfollow: given following → deletes row, decrements both counters, publishes event")
    void unfollow_givenFollowing_deletesAndDecrementsCountsAndPublishesEvent() {
        when(followerRepo.existsByFollowerIdAndFollowingId(followerId, targetId)).thenReturn(Mono.just(true));
        when(followerRepo.deleteByFollowerIdAndFollowingId(followerId, targetId)).thenReturn(Mono.empty());
        when(userRepo.incrementFollowerCount(targetId, -1)).thenReturn(Mono.empty());
        when(userRepo.incrementFollowingCount(followerId, -1)).thenReturn(Mono.empty());
        when(eventPublisher.publishUnfollowed(followerId, targetId)).thenReturn(Mono.empty());

        StepVerifier.create(followerService.unfollow(followerId, targetId))
                .verifyComplete();

        verify(followerRepo).deleteByFollowerIdAndFollowingId(followerId, targetId);
        verify(userRepo).incrementFollowerCount(targetId, -1);
        verify(userRepo).incrementFollowingCount(followerId, -1);
        verify(eventPublisher).publishUnfollowed(followerId, targetId);
    }

    // ── isFollowing ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("isFollowing: given existing relation → returns true")
    void isFollowing_givenExistingRelation_returnsTrue() {
        when(followerRepo.existsByFollowerIdAndFollowingId(followerId, targetId))
                .thenReturn(Mono.just(true));

        StepVerifier.create(followerService.isFollowing(followerId, targetId))
                .expectNext(true)
                .verifyComplete();
    }

    @Test
    @DisplayName("isFollowing: given no relation → returns false")
    void isFollowing_givenNoRelation_returnsFalse() {
        when(followerRepo.existsByFollowerIdAndFollowingId(followerId, targetId))
                .thenReturn(Mono.just(false));

        StepVerifier.create(followerService.isFollowing(followerId, targetId))
                .expectNext(false)
                .verifyComplete();
    }

    // ── getFollowers ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("getFollowers: given userId → maps Follower records to UserResponse page")
    void getFollowers_givenUserId_mapsAndReturnsPageResponse() {
        UUID followerUserId = UUID.randomUUID();
        Follower followerRecord = Follower.builder()
                .followerId(followerUserId).followingId(targetId).createdAt(Instant.now()).build();
        User followerUser = User.builder()
                .id(followerUserId).username("bob")
                .isVerified(false).isPrivate(false)
                .followerCount(0).followingCount(0).postCount(0).role("USER").build();

        when(followerRepo.findFollowersByUserId(targetId, 20)).thenReturn(Flux.just(followerRecord));
        when(userRepo.findByIdAndIsDeletedFalse(followerUserId)).thenReturn(Mono.just(followerUser));

        StepVerifier.create(followerService.getFollowers(targetId, null, 20))
                .expectNextMatches(page -> page.items().size() == 1
                        && "bob".equals(page.items().get(0).username()))
                .verifyComplete();
    }

    // ── getFollowing ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("getFollowing: given userId → maps Following records to UserResponse page")
    void getFollowing_givenUserId_mapsAndReturnsPageResponse() {
        UUID followingUserId = UUID.randomUUID();
        Follower followingRecord = Follower.builder()
                .followerId(followerId).followingId(followingUserId).createdAt(Instant.now()).build();
        User followingUser = User.builder()
                .id(followingUserId).username("charlie")
                .isVerified(false).isPrivate(false)
                .followerCount(0).followingCount(0).postCount(0).role("USER").build();

        when(followerRepo.findFollowingByUserId(followerId, 20)).thenReturn(Flux.just(followingRecord));
        when(userRepo.findByIdAndIsDeletedFalse(followingUserId)).thenReturn(Mono.just(followingUser));

        StepVerifier.create(followerService.getFollowing(followerId, null, 20))
                .expectNextMatches(page -> page.items().size() == 1
                        && "charlie".equals(page.items().get(0).username()))
                .verifyComplete();
    }
}
