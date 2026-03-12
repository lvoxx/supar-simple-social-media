package io.github.lvoxx.user_service.repository;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import io.github.lvoxx.user_service.entity.FollowRequest;
import io.github.lvoxx.user_service.entity.User;
import io.github.lvoxx.user_service.testcontainers.AbstractDatabaseTestContainer;
import reactor.test.StepVerifier;

@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FollowRequestRepositoryIT extends AbstractDatabaseTestContainer {

    @Autowired
    private FollowRequestRepository followRequestRepository;

    @Autowired
    private UserRepository userRepository;

    private UUID requesterId;
    private UUID targetId;

    @BeforeEach
    void setUp() {
        requesterId = createUser("requester_" + UUID.randomUUID().toString().substring(0, 8));
        targetId = createUser("target_" + UUID.randomUUID().toString().substring(0, 8));

        followRequestRepository.save(FollowRequest.builder()
                .id(UUID.randomUUID())
                .requesterId(requesterId)
                .targetId(targetId)
                .status("PENDING")
                .createdAt(Instant.now())
                .build()).block();
    }

    private UUID createUser(String username) {
        User user = User.builder()
                .id(UUID.randomUUID())
                .keycloakId(UUID.randomUUID())
                .username(username)
                .displayName("Test")
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

    // ── findByTargetIdAndStatus ───────────────────────────────────────────────

    @Test
    void findByTargetIdAndStatus_givenPendingStatus_returnsCorrectRequests() {
        StepVerifier.create(followRequestRepository.findByTargetIdAndStatus(targetId, "PENDING").collectList())
                .expectNextMatches(list -> !list.isEmpty()
                        && list.stream().allMatch(r -> "PENDING".equals(r.getStatus()))
                        && list.stream().anyMatch(r -> r.getRequesterId().equals(requesterId)))
                .verifyComplete();
    }

    @Test
    void findByTargetIdAndStatus_givenDifferentStatus_returnsEmpty() {
        StepVerifier.create(followRequestRepository.findByTargetIdAndStatus(targetId, "APPROVED").collectList())
                .expectNextMatches(list -> list.isEmpty())
                .verifyComplete();
    }

    @Test
    void findByTargetIdAndStatus_givenNonMatchingTarget_returnsEmpty() {
        UUID otherTarget = createUser("other_" + UUID.randomUUID().toString().substring(0, 8));

        StepVerifier.create(followRequestRepository.findByTargetIdAndStatus(otherTarget, "PENDING").collectList())
                .expectNextMatches(list -> list.isEmpty())
                .verifyComplete();
    }

    // ── existsByRequesterIdAndTargetIdAndStatus ────────────────────────────────

    @Test
    void existsByRequesterIdAndTargetIdAndStatus_givenExistingPending_returnsTrue() {
        StepVerifier.create(followRequestRepository
                .existsByRequesterIdAndTargetIdAndStatus(requesterId, targetId, "PENDING"))
                .expectNext(true)
                .verifyComplete();
    }

    @Test
    void existsByRequesterIdAndTargetIdAndStatus_givenDifferentStatus_returnsFalse() {
        StepVerifier.create(followRequestRepository
                .existsByRequesterIdAndTargetIdAndStatus(requesterId, targetId, "APPROVED"))
                .expectNext(false)
                .verifyComplete();
    }

    @Test
    void existsByRequesterIdAndTargetIdAndStatus_givenNonExistingCombo_returnsFalse() {
        StepVerifier.create(followRequestRepository
                .existsByRequesterIdAndTargetIdAndStatus(targetId, requesterId, "PENDING"))
                .expectNext(false)
                .verifyComplete();
    }
}
