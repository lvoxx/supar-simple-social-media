package io.github.lvoxx.user_service.repository;

import static org.assertj.core.api.Assertions.*;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import io.github.lvoxx.user_service.entity.User;
import io.github.lvoxx.user_service.testcontainers.AbstractDatabaseTestContainer;
import reactor.test.StepVerifier;

@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class UserRepositoryIT extends AbstractDatabaseTestContainer {

    @Autowired
    private UserRepository userRepository;

    private UUID savedUserId;
    private UUID keycloakId;

    @BeforeEach
    void setUp() {
        keycloakId = UUID.randomUUID();
        User user = User.builder()
                .id(UUID.randomUUID())
                .keycloakId(keycloakId)
                .username("alice_" + UUID.randomUUID().toString().substring(0, 8))
                .displayName("Alice Test")
                .isVerified(false)
                .isPrivate(false)
                .followerCount(0)
                .followingCount(0)
                .postCount(0)
                .role("USER")
                .status("ACTIVE")
                .isDeleted(false)
                .build();

        savedUserId = userRepository.save(user).block().getId();
    }

    // ── findByUsernameAndIsDeletedFalse ───────────────────────────────────────

    @Test
    void findByUsernameAndIsDeletedFalse_givenExistingUser_returnsUser() {
        User saved = userRepository.findById(savedUserId).block();

        StepVerifier.create(userRepository.findByUsernameAndIsDeletedFalse(saved.getUsername()))
                .expectNextMatches(u -> u.getId().equals(savedUserId))
                .verifyComplete();
    }

    @Test
    void findByUsernameAndIsDeletedFalse_givenNonExistingUsername_returnsEmpty() {
        StepVerifier.create(userRepository.findByUsernameAndIsDeletedFalse("nonexistent_xyz"))
                .verifyComplete();
    }

    @Test
    void findByUsernameAndIsDeletedFalse_givenDeletedUser_returnsEmpty() {
        User saved = userRepository.findById(savedUserId).block();
        saved.setIsDeleted(true);
        userRepository.save(saved).block();

        StepVerifier.create(userRepository.findByUsernameAndIsDeletedFalse(saved.getUsername()))
                .verifyComplete();
    }

    // ── findByIdAndIsDeletedFalse ─────────────────────────────────────────────

    @Test
    void findByIdAndIsDeletedFalse_givenExistingId_returnsUser() {
        StepVerifier.create(userRepository.findByIdAndIsDeletedFalse(savedUserId))
                .expectNextMatches(u -> u.getId().equals(savedUserId))
                .verifyComplete();
    }

    @Test
    void findByIdAndIsDeletedFalse_givenNonExistingId_returnsEmpty() {
        StepVerifier.create(userRepository.findByIdAndIsDeletedFalse(UUID.randomUUID()))
                .verifyComplete();
    }

    @Test
    void findByIdAndIsDeletedFalse_givenDeletedUser_returnsEmpty() {
        User saved = userRepository.findById(savedUserId).block();
        saved.setIsDeleted(true);
        userRepository.save(saved).block();

        StepVerifier.create(userRepository.findByIdAndIsDeletedFalse(savedUserId))
                .verifyComplete();
    }

    // ── existsByUsername ──────────────────────────────────────────────────────

    @Test
    void existsByUsername_givenExistingUsername_returnsTrue() {
        String username = userRepository.findById(savedUserId).block().getUsername();

        StepVerifier.create(userRepository.existsByUsername(username))
                .expectNext(true)
                .verifyComplete();
    }

    @Test
    void existsByUsername_givenNonExistingUsername_returnsFalse() {
        StepVerifier.create(userRepository.existsByUsername("definitely_not_exists_xyz"))
                .expectNext(false)
                .verifyComplete();
    }

    // ── searchByUsernameOrDisplayName ─────────────────────────────────────────

    @Test
    void searchByUsernameOrDisplayName_givenMatchingUsername_returnsUser() {
        String username = userRepository.findById(savedUserId).block().getUsername();
        String prefix = username.substring(0, 5);

        StepVerifier.create(userRepository.searchByUsernameOrDisplayName(prefix, 10).collectList())
                .expectNextMatches(list -> list.stream().anyMatch(u -> u.getId().equals(savedUserId)))
                .verifyComplete();
    }

    @Test
    void searchByUsernameOrDisplayName_givenMatchingDisplayName_returnsUser() {
        StepVerifier.create(userRepository.searchByUsernameOrDisplayName("Alice Test", 10).collectList())
                .expectNextMatches(list -> list.stream().anyMatch(u -> u.getId().equals(savedUserId)))
                .verifyComplete();
    }

    @Test
    void searchByUsernameOrDisplayName_givenLimit_respectsLimit() {
        for (int i = 0; i < 3; i++) {
            User user = User.builder()
                    .id(UUID.randomUUID())
                    .keycloakId(UUID.randomUUID())
                    .username("alice_extra_" + i + "_" + UUID.randomUUID().toString().substring(0, 4))
                    .displayName("Alice Extra " + i)
                    .isVerified(false)
                    .isPrivate(false)
                    .followerCount(0)
                    .followingCount(0)
                    .postCount(0)
                    .role("USER")
                    .status("ACTIVE")
                    .isDeleted(false)
                    .build();
            userRepository.save(user).block();
        }

        StepVerifier.create(userRepository.searchByUsernameOrDisplayName("alice", 2).collectList())
                .expectNextMatches(list -> list.size() <= 2)
                .verifyComplete();
    }

    // ── incrementFollowerCount ────────────────────────────────────────────────

    @Test
    void incrementFollowerCount_givenPositiveDelta_incrementsCount() {
        userRepository.incrementFollowerCount(savedUserId, 5).block();

        User updated = userRepository.findById(savedUserId).block();
        assertThat(updated.getFollowerCount()).isEqualTo(5);
    }

    @Test
    void incrementFollowerCount_givenNegativeDelta_decrementsCount() {
        userRepository.incrementFollowerCount(savedUserId, 10).block();
        userRepository.incrementFollowerCount(savedUserId, -3).block();

        User updated = userRepository.findById(savedUserId).block();
        assertThat(updated.getFollowerCount()).isEqualTo(7);
    }

    // ── incrementFollowingCount ───────────────────────────────────────────────

    @Test
    void incrementFollowingCount_givenPositiveDelta_incrementsCount() {
        userRepository.incrementFollowingCount(savedUserId, 2).block();

        User updated = userRepository.findById(savedUserId).block();
        assertThat(updated.getFollowingCount()).isEqualTo(2);
    }

    @Test
    void incrementFollowingCount_givenNegativeDelta_decrementsCount() {
        userRepository.incrementFollowingCount(savedUserId, 4).block();
        userRepository.incrementFollowingCount(savedUserId, -2).block();

        User updated = userRepository.findById(savedUserId).block();
        assertThat(updated.getFollowingCount()).isEqualTo(2);
    }

    // ── findByKeycloakId ──────────────────────────────────────────────────────

    @Test
    void findByKeycloakId_givenExistingKeycloakId_returnsUser() {
        StepVerifier.create(userRepository.findByKeycloakId(keycloakId))
                .expectNextMatches(u -> u.getId().equals(savedUserId))
                .verifyComplete();
    }

    @Test
    void findByKeycloakId_givenNonExistingKeycloakId_returnsEmpty() {
        StepVerifier.create(userRepository.findByKeycloakId(UUID.randomUUID()))
                .verifyComplete();
    }
}
