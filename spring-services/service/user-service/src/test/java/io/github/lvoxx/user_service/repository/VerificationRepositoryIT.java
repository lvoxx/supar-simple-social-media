package io.github.lvoxx.user_service.repository;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import io.github.lvoxx.user_service.entity.User;
import io.github.lvoxx.user_service.entity.Verification;
import io.github.lvoxx.user_service.testcontainers.AbstractDatabaseTestContainer;
import reactor.test.StepVerifier;

@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class VerificationRepositoryIT extends AbstractDatabaseTestContainer {

        @Autowired
        private VerificationRepository verificationRepository;

        @Autowired
        private UserRepository userRepository;

        private UUID userId;

        @BeforeEach
        void setUp() {
                User user = User.builder()
                                .id(UUID.randomUUID())
                                .keycloakId(UUID.randomUUID())
                                .username("verify_user_" + UUID.randomUUID().toString().substring(0, 8))
                                .displayName("Verify User")
                                .isVerified(false)
                                .isPrivate(false)
                                .followerCount(0)
                                .followingCount(0)
                                .postCount(0)
                                .role("USER")
                                .status("ACTIVE")
                                .isDeleted(false)
                                .build();
                userId = userRepository.save(user).block().getId();

                verificationRepository.save(Verification.builder()
                                .id(UUID.randomUUID())
                                .userId(userId)
                                .type("IDENTITY")
                                .status("PENDING")
                                .documentUrl("http://example.com/doc.pdf")
                                .createdAt(Instant.now())
                                .build()).block();
        }

        // ── findByUserId ──────────────────────────────────────────────────────────

        @Test
        void findByUserId_givenExistingVerifications_returnsList() {
                StepVerifier.create(verificationRepository.findByUserId(userId).collectList())
                                .expectNextMatches(list -> !list.isEmpty()
                                                && list.stream().allMatch(v -> userId.equals(v.getUserId())))
                                .verifyComplete();
        }

        @Test
        void findByUserId_givenNoVerifications_returnsEmpty() {
                StepVerifier.create(verificationRepository.findByUserId(UUID.randomUUID()).collectList())
                                .expectNextMatches(list -> list.isEmpty())
                                .verifyComplete();
        }

        @Test
        void findByUserId_givenMultipleVerifications_returnsAll() {
                verificationRepository.save(Verification.builder()
                                .id(UUID.randomUUID())
                                .userId(userId)
                                .type("BUSINESS")
                                .status("APPROVED")
                                .createdAt(Instant.now())
                                .build()).block();

                StepVerifier.create(verificationRepository.findByUserId(userId).collectList())
                                .expectNextMatches(list -> list.size() == 2)
                                .verifyComplete();
        }

        // ── existsByUserIdAndStatus ────────────────────────────────────────────────

        @Test
        void existsByUserIdAndStatus_givenMatchingPendingStatus_returnsTrue() {
                StepVerifier.create(verificationRepository.existsByUserIdAndStatus(userId, "PENDING"))
                                .expectNext(true)
                                .verifyComplete();
        }

        @Test
        void existsByUserIdAndStatus_givenDifferentStatus_returnsFalse() {
                StepVerifier.create(verificationRepository.existsByUserIdAndStatus(userId, "APPROVED"))
                                .expectNext(false)
                                .verifyComplete();
        }

        @Test
        void existsByUserIdAndStatus_givenDifferentUser_returnsFalse() {
                StepVerifier.create(verificationRepository.existsByUserIdAndStatus(UUID.randomUUID(), "PENDING"))
                                .expectNext(false)
                                .verifyComplete();
        }
}
