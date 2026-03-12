package io.github.lvoxx.user_service.repository;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import io.github.lvoxx.user_service.entity.Follower;
import io.github.lvoxx.user_service.entity.User;
import io.github.lvoxx.user_service.testcontainers.AbstractDatabaseTestContainer;
import reactor.test.StepVerifier;

@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FollowerRepositoryIT extends AbstractDatabaseTestContainer {

    @Autowired
    private FollowerRepository followerRepository;

    @Autowired
    private UserRepository userRepository;

    private UUID userId1;
    private UUID userId2;
    private UUID userId3;

    @BeforeEach
    void setUp() {
        userId1 = createUser("user1_" + UUID.randomUUID().toString().substring(0, 8));
        userId2 = createUser("user2_" + UUID.randomUUID().toString().substring(0, 8));
        userId3 = createUser("user3_" + UUID.randomUUID().toString().substring(0, 8));

        // user1 follows user2
        followerRepository.save(Follower.builder()
                .followerId(userId1)
                .followingId(userId2)
                .createdAt(Instant.now().minusSeconds(10))
                .build()).block();
    }

    private UUID createUser(String username) {
        User user = User.builder()
                .id(UUID.randomUUID())
                .keycloakId(UUID.randomUUID())
                .username(username)
                .displayName("Test User")
                .isVerified(false)
                .isPrivate(false)
                .followerCount(0)
                .followingCount(0)
                .postCount(0)
                .role("USER")
                .status("ACTIVE")
                .isDeleted(false)
                .build();
        return userRepository.save(user).block().getId();
    }

    // ── existsByFollowerIdAndFollowingId ──────────────────────────────────────

    @Test
    void existsByFollowerIdAndFollowingId_givenExistingRelation_returnsTrue() {
        StepVerifier.create(followerRepository.existsByFollowerIdAndFollowingId(userId1, userId2))
                .expectNext(true)
                .verifyComplete();
    }

    @Test
    void existsByFollowerIdAndFollowingId_givenNonExistingRelation_returnsFalse() {
        StepVerifier.create(followerRepository.existsByFollowerIdAndFollowingId(userId1, userId3))
                .expectNext(false)
                .verifyComplete();
    }

    // ── deleteByFollowerIdAndFollowingId ──────────────────────────────────────

    @Test
    void deleteByFollowerIdAndFollowingId_givenExistingRow_removesRow() {
        StepVerifier.create(followerRepository.deleteByFollowerIdAndFollowingId(userId1, userId2))
                .verifyComplete();

        StepVerifier.create(followerRepository.existsByFollowerIdAndFollowingId(userId1, userId2))
                .expectNext(false)
                .verifyComplete();
    }

    // ── findFollowersByUserId ─────────────────────────────────────────────────

    @Test
    void findFollowersByUserId_givenUserId_returnsFollowers() {
        // userId1 follows userId2, so userId2's followers include userId1
        StepVerifier.create(followerRepository.findFollowersByUserId(userId2, 10).collectList())
                .expectNextMatches(list ->
                        !list.isEmpty()
                                && list.stream().anyMatch(f -> f.getFollowerId().equals(userId1)))
                .verifyComplete();
    }

    @Test
    void findFollowersByUserId_givenLimit_respectsLimit() {
        // Add userId3 also follows userId2
        followerRepository.save(Follower.builder()
                .followerId(userId3)
                .followingId(userId2)
                .createdAt(Instant.now())
                .build()).block();

        StepVerifier.create(followerRepository.findFollowersByUserId(userId2, 1).collectList())
                .expectNextMatches(list -> list.size() == 1)
                .verifyComplete();
    }

    // ── findFollowingByUserId ─────────────────────────────────────────────────

    @Test
    void findFollowingByUserId_givenUserId_returnsFollowing() {
        StepVerifier.create(followerRepository.findFollowingByUserId(userId1, 10).collectList())
                .expectNextMatches(list ->
                        !list.isEmpty()
                                && list.stream().anyMatch(f -> f.getFollowingId().equals(userId2)))
                .verifyComplete();
    }

    @Test
    void findFollowingByUserId_givenUserWithNoFollowing_returnsEmpty() {
        StepVerifier.create(followerRepository.findFollowingByUserId(userId3, 10).collectList())
                .expectNextMatches(list -> list.isEmpty())
                .verifyComplete();
    }
}
